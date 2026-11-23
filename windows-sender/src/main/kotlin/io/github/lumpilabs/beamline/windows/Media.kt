package io.github.lumpilabs.beamline.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import io.github.lumpilabs.beamline.protocol.VideoSize

/** The screen and sound capture in beamline-media.dll, see src/main/cpp. */
@Suppress("FunctionName")
private interface MediaLibrary : Library {
  fun bm_now_ns(): Long

  fun bm_last_error(): String

  fun bm_screen_count(): Int

  fun bm_screen_info(index: Int, x: IntByReference, y: IntByReference, width: IntByReference, height: IntByReference): Int

  fun bm_video_open(screen: Int, width: Int, height: Int, fps: Int, bitrate: Int): Pointer?

  fun bm_video_read(video: Pointer, buffer: Pointer, capacity: Int, ptsNs: LongByReference, flags: IntByReference, timeoutMs: Int): Int

  fun bm_video_error(video: Pointer): String

  fun bm_video_encoder(video: Pointer): String

  fun bm_video_hardware(video: Pointer): Int

  fun bm_video_request_keyframe(video: Pointer)

  fun bm_video_set_bitrate(video: Pointer, bitrate: Int)

  fun bm_video_close(video: Pointer)

  fun bm_audio_open(): Pointer?

  fun bm_audio_read(audio: Pointer, buffer: Pointer, capacity: Int, ptsNs: LongByReference, timeoutMs: Int): Int

  fun bm_audio_error(audio: Pointer): String

  fun bm_audio_close(audio: Pointer)

  fun bm_wifi_streaming_start(): Pointer?

  fun bm_wifi_streaming_stop(handle: Pointer)
}

private val native: MediaLibrary by lazy { Native.load("beamline-media", MediaLibrary::class.java) }

class CaptureException(message: String) : Exception(message)

/** The clock every timestamp from the capture is on: the performance counter, in nanoseconds. */
object MediaClock {
  fun nowNs(): Long = native.bm_now_ns()
}

/**
 * Keeps the Wi-Fi out of power saving while open, where it would hold frames back and send them in
 * bunches: Windows' media streaming mode, as the TV holds a Wi-Fi lock. Does nothing without Wi-Fi.
 */
class WifiStreamingMode private constructor(private val handle: Pointer) : AutoCloseable {
  override fun close() = native.bm_wifi_streaming_stop(handle)

  companion object {
    /** Null without a connected Wi-Fi adapter. */
    fun start(): WifiStreamingMode? = native.bm_wifi_streaming_start()?.let(::WifiStreamingMode)
  }
}

/** A screen of the desktop; [index] 0 is the primary one. */
data class Screen(val index: Int, val x: Int, val y: Int, val width: Int, val height: Int, val primary: Boolean) {
  val size: VideoSize get() = VideoSize(width, height)

  companion object {
    fun list(): List<Screen> =
      (0 until native.bm_screen_count()).mapNotNull { index ->
        val x = IntByReference()
        val y = IntByReference()
        val width = IntByReference()
        val height = IntByReference()
        when (native.bm_screen_info(index, x, y, width, height)) {
          -1 -> null
          else -> Screen(index, x.value, y.value, width.value, height.value, primary = x.value == 0 && y.value == 0)
        }
      }
  }
}

/**
 * One screen captured and encoded to H.264 at [size]. [read] and [close] belong to one thread; the
 * other calls may come from any.
 */
class ScreenCapture(screen: Int, val size: VideoSize, val fps: Int, bitrate: Int) : AutoCloseable {
  private val handle = native.bm_video_open(screen, size.width, size.height, fps, bitrate)
    ?: throw CaptureException(native.bm_last_error())
  private val nativeBuffer = Memory(MAX_FRAME_BYTES.toLong())
  private val pts = LongByReference()
  private val flags = IntByReference()
  private val lock = Any()
  private var closed = false

  val encoderName: String = native.bm_video_encoder(handle)
  val hardware: Boolean = native.bm_video_hardware(handle) != 0

  /** The last frame [read] returned. */
  var frame = ByteArray(256 * 1024)
    private set
  var ptsNs = 0L
    private set
  var keyframe = false
    private set

  /**
   * Waits up to [timeoutMs] for the next frame and returns its length; 0 when none came, [SOURCE_CHANGED]
   * when the screen changed size. Throws when capture failed.
   */
  fun read(timeoutMs: Int): Int {
    val length = native.bm_video_read(handle, nativeBuffer, MAX_FRAME_BYTES, pts, flags, timeoutMs)
    if (length == -1) throw CaptureException(native.bm_video_error(handle))
    if (length == -2) return SOURCE_CHANGED
    if (length <= 0) return 0
    if (frame.size < length) frame = ByteArray(length + length / 2)
    nativeBuffer.read(0, frame, 0, length)
    ptsNs = pts.value
    keyframe = flags.value and 1 != 0 || H264.isKeyframe(frame, length)
    return length
  }

  fun requestKeyframe() =
    synchronized(lock) {
      if (!closed) native.bm_video_request_keyframe(handle)
    }

  fun setBitrate(bps: Int) =
    synchronized(lock) {
      if (!closed) native.bm_video_set_bitrate(handle, bps)
    }

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      native.bm_video_close(handle)
    }
  }

  companion object {
    const val SOURCE_CHANGED = -2

    /** Far more than a keyframe at the highest bitrate. */
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
  }
}

/** What the default sound output plays, as 48 kHz stereo float. [read] and [close] belong to one thread. */
class SoundCapture : AutoCloseable {
  private val handle = native.bm_audio_open() ?: throw CaptureException(native.bm_last_error())
  private val nativeBuffer = Memory(MAX_FRAMES * CHANNELS * 4L)
  private val pts = LongByReference()
  private var closed = false

  /** Samples of the last [read], interleaved. */
  val samples = FloatArray(MAX_FRAMES * CHANNELS)
  var ptsNs = 0L
    private set

  /** Waits up to [timeoutMs] for sound and returns how many frames came. Throws when capture failed. */
  fun read(timeoutMs: Int): Int {
    val frames = native.bm_audio_read(handle, nativeBuffer, MAX_FRAMES, pts, timeoutMs)
    if (frames < 0) throw CaptureException(native.bm_audio_error(handle))
    if (frames > 0) {
      nativeBuffer.read(0, samples, 0, frames * CHANNELS)
      ptsNs = pts.value
    }
    return frames
  }

  override fun close() {
    if (closed) return
    closed = true
    native.bm_audio_close(handle)
  }

  companion object {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    private const val MAX_FRAMES = 4_800
  }
}
