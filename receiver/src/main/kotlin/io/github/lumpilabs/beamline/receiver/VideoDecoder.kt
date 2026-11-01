package io.github.lumpilabs.beamline.receiver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
import io.github.lumpilabs.beamline.protocol.StreamConfig
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder that renders straight to [surface] at the times [clock] schedules.
 *
 * Frames wait here still encoded, in a jitter buffer of a few hundred milliseconds, and go into
 * the decoder about as long before they're due as decoding takes, see [DecodeLead]. Waiting before
 * decoding matters: a decoder has only a handful of output buffers, so holding decoded frames that
 * long would stall it.
 *
 * If the buffer overflows (the network delivered far more than can be shown), queued frames are
 * dropped and [Listener.onNeedKeyframe] asks the sender for a fresh keyframe.
 *
 * Shown, late and dropped frames are counted in [stats]. All MediaCodec calls happen on one
 * handler thread.
 */
class VideoDecoder(
  surface: Surface,
  config: StreamConfig,
  private val clock: PlaybackClock,
  private val stats: PlaybackStats,
  private val listener: Listener,
) {
  interface Listener {
    fun onNeedKeyframe()

    /** Called once, on the decoder's thread, when the codec broke; the decoder does nothing more. */
    fun onDecoderError(error: Exception)
  }

  private val thread = HandlerThread("beamline-decoder").also { it.start() }
  private val handler = Handler(thread.looper)
  private val codec: MediaCodec
  private val freeInputs = ArrayDeque<Int>()
  private val pending = ArrayDeque<Pending>()

  /** Frames in the decoder, by timestamp: when they went in and when they're due. */
  private val decoding = LinkedHashMap<Long, Decoding>()
  private val lead = DecodeLead()
  private val feedLater = Runnable { feed() }
  private var waitingForKeyframe = true
  private var skippedLast = false

  /** Released, or broken by an error: either way the codec takes no more calls. */
  private var stopped = false

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
      stopped = true
      handler.removeCallbacks(feedLater)
      runCatching { codec.stop() }
      runCatching { codec.release() }
      done.countDown()
    }
    if (posted) done.await(1, TimeUnit.SECONDS)
    thread.quitSafely()
  }

  private fun enqueue(frame: Frame) {
    if (stopped) return
    val isConfig = frame.hasFlag(FrameFlags.CODEC_CONFIG)
    val isKeyframe = frame.hasFlag(FrameFlags.KEYFRAME)
    if (waitingForKeyframe && !isConfig && !isKeyframe) {
      stats.framesDropped.incrementAndGet()
      return
    }
    if (isKeyframe) waitingForKeyframe = false

    if (pending.size >= MAX_PENDING && !isConfig) {
      // Far more queued than any sensible buffer: start over from the next keyframe.
      val queued = pending.size
      pending.removeAll { !it.frame.hasFlag(FrameFlags.CODEC_CONFIG) }
      stats.framesDropped.addAndGet((queued - pending.size).toLong())
      if (!isKeyframe) {
        stats.framesDropped.incrementAndGet()
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
    if (stopped) return
    handler.removeCallbacks(feedLater)
    val now = System.nanoTime()
    try {
      while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
        val next = pending.first()
        val dueAt = next.renderAt?.minus(lead.ns)
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
          stats.framesDropped.incrementAndGet()
          waitingForKeyframe = true
          listener.onNeedKeyframe()
          continue
        }
        buffer.clear()
        buffer.put(frame.body)
        var flags = 0
        if (frame.hasFlag(FrameFlags.CODEC_CONFIG)) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        if (frame.hasFlag(FrameFlags.KEYFRAME)) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        next.renderAt?.let { remember(frame.ptsUs, Decoding(queuedAt = now, renderAt = it)) }
        codec.queueInputBuffer(index, 0, frame.body.size, frame.ptsUs, flags)
      }
    } catch (e: IllegalStateException) {
      // A broken codec throws here, sometimes before its error callback comes.
      fail(e)
    }
  }

  /**
   * The codec stopped working, typically because the TV app was left and its surface went away.
   * It's never called again; the listener decides about a new one.
   */
  private fun fail(error: IllegalStateException) {
    if (stopped) return
    stopped = true
    handler.removeCallbacks(feedLater)
    listener.onDecoderError(error)
  }

  private fun remember(ptsUs: Long, frame: Decoding) {
    decoding[ptsUs] = frame
    if (decoding.size > MAX_DECODING) decoding.remove(decoding.keys.first())
  }

  private inner class Callback : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
      if (stopped) return
      freeInputs.addLast(index)
      feed()
    }

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
      if (stopped) return
      val now = System.nanoTime()
      val decoded = decoding.remove(info.presentationTimeUs)
      val target = decoded?.renderAt
      if (decoded != null) {
        lead.onDecoded(now - decoded.queuedAt)
        stats.decodeMs = lead.decodeNs?.let { it / 1_000_000 }
      }
      if (target != null && now - target >= LATE_NS) stats.framesLate.incrementAndGet()
      // Far behind, as after a stall: when the content runs at the screen's own rate, showing every
      // frame would leave the picture behind the sound for good. Skipping catches up; never two in
      // a row, so a decoder that is always this slow still shows every other frame.
      val skip = target != null && now - target > SKIP_LATE_NS && !skippedLast
      skippedLast = skip
      if (skip) stats.framesDropped.incrementAndGet() else stats.framesShown.incrementAndGet()
      val showAt = try {
        when {
          skip -> {
            codec.releaseOutputBuffer(index, false)
            return
          }
          // On the TV's clock: releaseOutputBuffer(index, true) would pass on the phone's timestamp.
          target == null || target <= now + ON_TIME_NS -> now.also { codec.releaseOutputBuffer(index, it) }
          else -> minOf(target, now + MAX_HOLD_NS).also { codec.releaseOutputBuffer(index, it) }
        }
      } catch (e: IllegalStateException) {
        fail(e)
        return
      }
      val offset = showAt - info.presentationTimeUs * 1000
      renderOffsetNs = renderOffsetNs?.let { it + (offset - it) / 8 } ?: offset
    }

    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
  }

  private class Pending(val frame: Frame, val renderAt: Long?)

  private class Decoding(val queuedAt: Long, val renderAt: Long)

  companion object {
    /**
     * The codecs this TV decodes in hardware at [size] and [fps]: H.264 always, HEVC where the chip
     * has it (the Fire TV Stick 4K Max does).
     */
    fun supportedCodecs(size: VideoSize, fps: Int): List<String> {
      val hevc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder &&
          !isSoftwareOnly(info) &&
          info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
          runCatching {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
              ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
          }.getOrDefault(false)
      }
      return if (hevc) listOf(Codecs.H264, Codecs.HEVC) else listOf(Codecs.H264)
    }

    /** MediaCodecInfo.isSoftwareOnly is Android 10; Fire OS 7 is Android 9, where the name tells. */
    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        info.isSoftwareOnly
      } else {
        info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
      }

    private const val MAX_INPUT_SIZE = 4 * 1024 * 1024

    /** About two seconds at 60 fps; a normal buffer holds a few hundred milliseconds. */
    private const val MAX_PENDING = 120

    /** Frames due within this are shown at once. */
    private const val ON_TIME_NS = 4_000_000L

    /**
     * Never hold a decoded frame longer than this: the decoder has only a few output buffers.
     * [DecodeLead.MAX_NS] stays within it.
     */
    private const val MAX_HOLD_NS = 100_000_000L

    /** A frame decoded this long after its time misses at least one refresh of a 60 Hz screen. */
    private const val LATE_NS = 16_700_000L

    /** A frame decoded this long after its time is skipped: a whole frame at 25 fps. */
    private const val SKIP_LATE_NS = 40_000_000L

    private const val MAX_DECODING = 256
  }
}
