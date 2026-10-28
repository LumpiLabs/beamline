package io.github.lumpilabs.beamline.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Hardware H.264 encoder fed by a surface. Encoded output is handed to [sink] on a dedicated
 * thread as soon as it leaves the codec.
 */
class ScreenEncoder(size: VideoSize, fps: Int, bitrate: Int, private val sink: Sink) {
  interface Sink {
    /** SPS and PPS, needed by the decoder before the first frame. */
    fun onCodecConfig(data: ByteArray)

    fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean)

    fun onError(error: Exception)
  }

  private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
  private val running = AtomicBoolean(true)

  /** Draw here (for example from a virtual display) to encode. */
  val inputSurface: Surface

  private val drainThread: Thread

  init {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
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
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = codec.createInputSurface()
    codec.start()
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

  private fun drain() {
    val info = MediaCodec.BufferInfo()
    try {
      while (running.get()) {
        val index = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
        if (index < 0) continue
        val buffer = codec.getOutputBuffer(index)
        val data = ByteArray(info.size)
        if (buffer != null && info.size > 0) {
          buffer.position(info.offset)
          buffer.limit(info.offset + info.size)
          buffer.get(data)
        }
        val flags = info.flags
        val ptsUs = info.presentationTimeUs
        codec.releaseOutputBuffer(index, false)
        when {
          flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> sink.onCodecConfig(data)
          data.isNotEmpty() -> sink.onFrame(data, ptsUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
        }
        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
      }
    } catch (e: Exception) {
      if (running.get()) sink.onError(e)
    }
  }

  companion object {
    private const val DRAIN_TIMEOUT_US = 100_000L
    private const val REPEAT_AFTER_US = 100_000L

    /**
     * About 1/16 bit per pixel per frame within 2 to 12 Mbit/s: about 9 Mbit/s for a landscape
     * phone at 2336x1072, sharp for video and text without overloading a busy Wi-Fi.
     */
    fun bitrateFor(size: VideoSize, fps: Int): Int =
      (size.width.toLong() * size.height * fps / 16).toInt().coerceIn(2_000_000, 12_000_000)
  }
}
