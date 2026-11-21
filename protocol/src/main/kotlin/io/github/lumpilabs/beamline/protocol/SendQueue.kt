package io.github.lumpilabs.beamline.protocol

import java.io.IOException
import kotlin.concurrent.thread

/**
 * Sends frames on a thread of its own, so a slow network never holds up the video encoder or the
 * sound, and keeps the video backlog short instead of letting it grow for seconds.
 *
 * Control messages and sound go out before waiting video. Once the oldest waiting video frame has
 * waited longer than [maxBacklogNs], the waiting video is thrown away and later frames are skipped
 * up to the next keyframe, because frames after a gap can't be decoded without one;
 * [Listener.onCongested] then asks for a keyframe and a lower bitrate. Codec configs are kept.
 *
 * Video frames are copied into reused arrays: the encoder reuses its own. [nanoTime] is replaceable
 * for tests.
 */
class SendQueue(
  private val listener: Listener,
  private val maxBacklogNs: Long = DEFAULT_MAX_BACKLOG_NS,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  interface Listener {
    /** Video was thrown away because the network couldn't carry it. Called on the offering thread. */
    fun onCongested()

    /** Writing failed, so the connection is gone. Called once, on the sending thread. */
    fun onWriteFailed(error: IOException)
  }

  internal class Item(
    val type: FrameType,
    val flags: Int,
    val ptsUs: Long,
    val body: ByteArray,
    val length: Int,
    val queuedAt: Long,
  ) {
    val isVideoMedia get() = type == FrameType.VIDEO && flags and FrameFlags.CODEC_CONFIG == 0
  }

  private val lock = Object()

  // Guarded by lock.
  private val urgent = ArrayDeque<Item>()
  private val video = ArrayDeque<Item>()
  private val spare = ArrayDeque<ByteArray>()
  private var skippingVideo = false
  private var closed = false
  private var longestWaitNs = 0L
  private var dropped = 0L

  /** Video frames thrown away so far. */
  val droppedVideo: Long get() = synchronized(lock) { dropped }

  /** Queues the first [length] bytes of [body] as one frame; [body] may be reused once this returns. */
  fun offer(type: FrameType, flags: Int, ptsUs: Long, body: ByteArray, length: Int) {
    var congested = false
    synchronized(lock) {
      if (closed) return
      val now = nanoTime()
      if (type != FrameType.VIDEO) {
        urgent.addLast(Item(type, flags, ptsUs, body.copyOf(length), length, now))
      } else if (flags and FrameFlags.CODEC_CONFIG != 0) {
        video.addLast(Item(type, flags, ptsUs, body.copyOf(length), length, now))
      } else {
        congested = shedIfBackedUp(now)
        if (skippingVideo && flags and FrameFlags.KEYFRAME == 0) {
          dropped++
        } else {
          skippingVideo = false
          val copy = obtain(length)
          System.arraycopy(body, 0, copy, 0, length)
          video.addLast(Item(type, flags, ptsUs, copy, length, now))
        }
      }
      lock.notifyAll()
    }
    if (congested) listener.onCongested()
  }

  /** A new video stream starts (another size or codec): what waits of the old one is of no use. */
  fun clearVideo() {
    synchronized(lock) {
      video.forEach(::recycle)
      video.clear()
      skippingVideo = false
    }
  }

  /** The longest a video frame waited to be sent since the last call, counting those still waiting. */
  fun takeLongestWaitNs(): Long =
    synchronized(lock) {
      val waiting = video.firstOrNull { it.isVideoMedia }?.let { nanoTime() - it.queuedAt } ?: 0L
      maxOf(longestWaitNs, waiting).also { longestWaitNs = 0 }
    }

  /** Starts writing to [writer]; stops at [close] or when writing fails. */
  fun start(writer: FrameWriter) {
    thread(name = "beamline-send", isDaemon = true) { run(writer) }
  }

  fun close() {
    synchronized(lock) {
      closed = true
      urgent.clear()
      video.clear()
      lock.notifyAll()
    }
  }

  /** The next frame to write: control and sound first, then video in order. Null when empty. */
  internal fun poll(): Item? =
    synchronized(lock) {
      urgent.removeFirstOrNull()?.let { return it }
      val item = video.removeFirstOrNull() ?: return null
      if (item.isVideoMedia) longestWaitNs = maxOf(longestWaitNs, nanoTime() - item.queuedAt)
      item
    }

  private fun run(writer: FrameWriter) {
    while (true) {
      val item = synchronized(lock) {
        while (!closed && urgent.isEmpty() && video.isEmpty()) lock.wait()
        if (closed) return
        poll()
      } ?: continue
      try {
        writer.write(item.type, item.flags, item.ptsUs, item.body, item.length)
      } catch (e: IOException) {
        close()
        listener.onWriteFailed(e)
        return
      } finally {
        synchronized(lock) { recycle(item) }
      }
    }
  }

  /** Throws away the waiting video once its oldest frame waited too long. Returns whether it did. */
  private fun shedIfBackedUp(now: Long): Boolean {
    val oldest = video.firstOrNull { it.isVideoMedia } ?: return false
    if (now - oldest.queuedAt <= maxBacklogNs) return false
    longestWaitNs = maxOf(longestWaitNs, now - oldest.queuedAt)
    val kept = video.filterNot { it.isVideoMedia }
    video.filter { it.isVideoMedia }.forEach {
      recycle(it)
      dropped++
    }
    video.clear()
    video.addAll(kept)
    skippingVideo = true
    return true
  }

  private fun obtain(length: Int): ByteArray {
    val index = spare.indexOfFirst { it.size >= length }
    return if (index >= 0) spare.removeAt(index) else ByteArray(length + length / 2)
  }

  private fun recycle(item: Item) {
    if (item.isVideoMedia && spare.size < MAX_SPARE) spare.addLast(item.body)
  }

  companion object {
    /**
     * Longer than an ordinary Wi-Fi hiccup, which the TV's buffer hides, but short enough that what
     * gets through is still worth showing.
     */
    const val DEFAULT_MAX_BACKLOG_NS = 250_000_000L

    /** In low-latency mode the TV is less than this behind the phone; older frames are of no use. */
    const val LOW_LATENCY_MAX_BACKLOG_NS = 100_000_000L

    /**
     * The system send buffer to set for video at [bps]: about a tenth of a second once Linux has
     * doubled it. More than a Wi-Fi round trip needs, little enough that a slow network shows up
     * here, as frames waiting in the queue, where they can still be skipped. A fixed size would
     * hide several times as long at low rates.
     */
    fun socketBufferBytes(bps: Int): Int =
      (bps.toLong() * SOCKET_BUFFER_MS / 1000 / 8 / 2).toInt().coerceIn(MIN_SOCKET_BUFFER_BYTES, MAX_SOCKET_BUFFER_BYTES)

    const val MAX_SOCKET_BUFFER_BYTES = 64 * 1024
    private const val MIN_SOCKET_BUFFER_BYTES = 8 * 1024
    private const val SOCKET_BUFFER_MS = 100

    private const val MAX_SPARE = 8
  }
}
