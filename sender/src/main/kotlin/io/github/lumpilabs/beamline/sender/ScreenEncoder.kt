package io.github.lumpilabs.beamline.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Hardware H.264 or HEVC encoder ([videoCodec], a [Codecs] name) fed by a surface. Encoded output
 * is handed to [sink] on a dedicated thread as soon as it leaves the codec.
 */
class ScreenEncoder(videoCodec: String, size: VideoSize, fps: Int, bitrate: Int, private val sink: Sink) {
  interface Sink {
    /** Parameter sets (SPS and PPS, plus VPS for HEVC), needed by the decoder before the first frame. */
    fun onCodecConfig(data: ByteArray)

    /**
     * One encoded frame: the first [length] bytes of [data]. [data] is reused for the next frame,
     * so it must be sent or copied before this returns.
     */
    fun onFrame(data: ByteArray, length: Int, ptsUs: Long, keyframe: Boolean)

    fun onError(error: Exception)
  }

  private val codec = when (videoCodec) {
    Codecs.H264 -> MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    // The first HEVC encoder listed can be a software one, far too slow for the screen.
    Codecs.HEVC -> MediaCodec.createByCodecName(
      hardwareEncoder(Codecs.HEVC, size, fps) ?: throw IllegalArgumentException("No hardware HEVC encoder"),
    )
    else -> throw IllegalArgumentException("Unsupported video codec $videoCodec")
  }
  private val running = AtomicBoolean(true)

  /** Draw here (for example from a virtual display) to encode. */
  val inputSurface: Surface

  private val drainThread: Thread

  init {
    val format = MediaFormat.createVideoFormat(mimeFor(videoCodec), size.width, size.height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
      // Keyframes come on demand; a long interval keeps the bitrate steady.
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
      // A static screen produces no frames; repeat the last one so the TV keeps getting data.
      setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
      setInteger(MediaFormat.KEY_PRIORITY, 0)
      setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
      setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
    }
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      inputSurface = codec.createInputSurface()
      codec.start()
    } catch (e: Exception) {
      codec.release()
      throw e
    }
    drainThread = thread(name = "beamline-encoder", isDaemon = true) { drain() }
  }

  fun requestKeyframe() {
    if (!running.get()) return
    runCatching {
      codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }
  }

  fun setBitrate(bitrate: Int) {
    if (!running.get()) return
    runCatching {
      codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
    }
  }

  fun stop() {
    if (!running.getAndSet(false)) return
    if (Thread.currentThread() != drainThread) drainThread.join(DRAIN_TIMEOUT_US / 1000 * 5)
    runCatching { codec.stop() }
    runCatching { codec.release() }
    inputSurface.release()
  }

  /**
   * Copies each frame into one reused array: at 9 Mbit/s, a new array per frame would leave over a
   * megabyte of garbage a second for the phone to collect.
   */
  private fun drain() {
    val info = MediaCodec.BufferInfo()
    var frame = ByteArray(INITIAL_FRAME_BYTES)
    try {
      while (running.get()) {
        val index = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
        if (index < 0) continue
        val buffer = codec.getOutputBuffer(index)
        val length = if (buffer != null) info.size else 0
        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!config && frame.size < length) frame = ByteArray(length + length / 2)
        // Codec configs are kept for resending, so they get an array of their own.
        val target = if (config) ByteArray(length) else frame
        if (buffer != null && length > 0) {
          buffer.position(info.offset)
          buffer.limit(info.offset + length)
          buffer.get(target, 0, length)
        }
        val flags = info.flags
        val ptsUs = info.presentationTimeUs
        codec.releaseOutputBuffer(index, false)
        when {
          config -> sink.onCodecConfig(target)
          length > 0 -> sink.onFrame(target, length, ptsUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
        }
        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
      }
    } catch (e: Exception) {
      if (running.get()) sink.onError(e)
    }
  }

  companion object {
    private const val DRAIN_TIMEOUT_US = 100_000L

    /** Enough for most frames at 9 Mbit/s; keyframes grow it once. */
    private const val INITIAL_FRAME_BYTES = 256 * 1024
    private const val REPEAT_AFTER_US = 100_000L

    /**
     * About 1/16 bit per pixel per frame within 2 to 12 Mbit/s: about 9 Mbit/s for a landscape
     * phone at 2336x1072, sharp for video and text without overloading a busy Wi-Fi.
     */
    fun bitrateFor(size: VideoSize, fps: Int): Int =
      (size.width.toLong() * size.height * fps / 16).toInt().coerceIn(2_000_000, 12_000_000)

    /** The name of a hardware encoder for [videoCodec] at [size] and [fps], or null if the phone has none. */
    fun hardwareEncoder(videoCodec: String, size: VideoSize, fps: Int): String? {
      val mime = mimeFor(videoCodec)
      return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
        info.isEncoder &&
          info.isHardwareAccelerated &&
          info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
          runCatching {
            info.getCapabilitiesForType(mime).videoCapabilities
              ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
          }.getOrDefault(false)
      }?.name
    }

    private fun mimeFor(videoCodec: String): String =
      when (videoCodec) {
        Codecs.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        Codecs.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> throw IllegalArgumentException("Unsupported video codec $videoCodec")
      }
  }
}
