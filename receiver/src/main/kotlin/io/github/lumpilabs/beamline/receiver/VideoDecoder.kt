package io.github.lumpilabs.beamline.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
import io.github.lumpilabs.beamline.protocol.StreamConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder that renders straight to [surface] at the times [clock] schedules.
 *
 * Frames wait here still encoded, in a jitter buffer of a few hundred milliseconds, and go into
 * the decoder [DECODE_LEAD_NS] before they're due. Waiting before decoding matters: a decoder has
 * only a handful of output buffers, so holding decoded frames that long would stall it.
 *
 * If the buffer overflows (the network delivered far more than can be shown), queued frames are
 * dropped and [Listener.onNeedKeyframe] asks the sender for a fresh keyframe.
 *
 * All MediaCodec calls happen on one handler thread.
 */
class VideoDecoder(
  surface: Surface,
  config: StreamConfig,
  private val clock: PlaybackClock,
  private val listener: Listener,
) {
  interface Listener {
    fun onNeedKeyframe()

    fun onFrameRendered()

    fun onDecoderError(error: Exception)
  }

  private val thread = HandlerThread("beamline-decoder").also { it.start() }
  private val handler = Handler(thread.looper)
  private val codec: MediaCodec
  private val freeInputs = ArrayDeque<Int>()
  private val pending = ArrayDeque<Pending>()
  private val renderTimes = LinkedHashMap<Long, Long>()
  private val feedLater = Runnable { feed() }
  private var waitingForKeyframe = true
  private var released = false

  /** Local System.nanoTime minus sender time (ns) at which frames are shown, smoothed. */
  @Volatile var renderOffsetNs: Long? = null
    private set

  init {
    val mime = when (config.codec) {
      Codecs.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
      Codecs.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
      else -> throw IllegalArgumentException("Unsupported video codec ${config.codec}")
    }
    val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
      setInteger(MediaFormat.KEY_PRIORITY, 0)
      setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }
    codec = MediaCodec.createDecoderByType(mime)
    codec.setCallback(Callback(), handler)
    codec.configure(format, surface, null, 0)
    codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
    codec.start()
  }

  fun submit(frame: Frame) {
    handler.post { enqueue(frame) }
  }

  /** Stops and releases the codec, waiting briefly so the surface is free for a new decoder. */
  fun release() {
    val done = CountDownLatch(1)
    val posted = handler.post {
      released = true
      handler.removeCallbacks(feedLater)
      runCatching { codec.stop() }
      runCatching { codec.release() }
      done.countDown()
    }
    if (posted) done.await(1, TimeUnit.SECONDS)
    thread.quitSafely()
  }

  private fun enqueue(frame: Frame) {
    if (released) return
    val isConfig = frame.hasFlag(FrameFlags.CODEC_CONFIG)
    val isKeyframe = frame.hasFlag(FrameFlags.KEYFRAME)
    if (waitingForKeyframe && !isConfig && !isKeyframe) return
    if (isKeyframe) waitingForKeyframe = false

    if (pending.size >= MAX_PENDING && !isConfig) {
      // Far more queued than any sensible buffer: start over from the next keyframe.
      pending.removeAll { !it.frame.hasFlag(FrameFlags.CODEC_CONFIG) }
      if (!isKeyframe) {
        waitingForKeyframe = true
        listener.onNeedKeyframe()
        return
      }
    }
    val renderAt = if (isConfig) null else clock.schedule(frame.ptsUs)
    pending.addLast(Pending(frame, renderAt))
    feed()
  }

  /** Moves due frames into the decoder and wakes up again when the next one is due. */
  private fun feed() {
    if (released) return
    handler.removeCallbacks(feedLater)
    val now = System.nanoTime()
    while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
      val next = pending.first()
      val dueAt = next.renderAt?.minus(DECODE_LEAD_NS)
      if (dueAt != null && dueAt > now) {
        handler.postDelayed(feedLater, (dueAt - now) / 1_000_000 + 1)
        return
      }
      pending.removeFirst()
      val index = freeInputs.removeFirst()
      val buffer = codec.getInputBuffer(index) ?: continue
      val frame = next.frame
      if (frame.body.size > buffer.capacity()) {
        freeInputs.addFirst(index)
        waitingForKeyframe = true
        listener.onNeedKeyframe()
        continue
      }
      buffer.clear()
      buffer.put(frame.body)
      var flags = 0
      if (frame.hasFlag(FrameFlags.CODEC_CONFIG)) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
      if (frame.hasFlag(FrameFlags.KEYFRAME)) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
      next.renderAt?.let { rememberRenderTime(frame.ptsUs, it) }
      codec.queueInputBuffer(index, 0, frame.body.size, frame.ptsUs, flags)
    }
  }

  private fun rememberRenderTime(ptsUs: Long, renderAt: Long) {
    renderTimes[ptsUs] = renderAt
    if (renderTimes.size > MAX_RENDER_TIMES) renderTimes.remove(renderTimes.keys.first())
  }

  private inner class Callback : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
      if (released) return
      freeInputs.addLast(index)
      feed()
    }

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
      if (released) return
      val now = System.nanoTime()
      val target = renderTimes.remove(info.presentationTimeUs)
      val showAt = if (target == null || target <= now + ON_TIME_NS) {
        codec.releaseOutputBuffer(index, true)
        now
      } else {
        minOf(target, now + MAX_HOLD_NS).also { codec.releaseOutputBuffer(index, it) }
      }
      val offset = showAt - info.presentationTimeUs * 1000
      renderOffsetNs = renderOffsetNs?.let { it + (offset - it) / 8 } ?: offset
      listener.onFrameRendered()
    }

    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
      if (!released) listener.onDecoderError(e)
    }

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
  }

  private class Pending(val frame: Frame, val renderAt: Long?)

  private companion object {
    const val MAX_INPUT_SIZE = 4 * 1024 * 1024

    /** About two seconds at 60 fps; a normal buffer holds a few hundred milliseconds. */
    const val MAX_PENDING = 120

    /** How long before its time a frame goes into the decoder; decoding takes 10 to 30 ms. */
    const val DECODE_LEAD_NS = 60_000_000L

    /** Frames due within this are shown at once. */
    const val ON_TIME_NS = 4_000_000L

    /** Never hold a decoded frame longer than this: the decoder has only a few output buffers. */
    const val MAX_HOLD_NS = 100_000_000L

    const val MAX_RENDER_TIMES = 256
  }
}
