package io.github.lumpilabs.beamline.windows

import club.minnced.opus.util.OpusLibrary
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import tomp2p.opuswrapper.Opus

/** libopus for 48 kHz stereo in 20 ms frames, as the phone sends: 128 kbit/s, music rather than speech. */
class OpusEncoder : AutoCloseable {
  private val encoder: PointerByReference
  private val output = ByteBuffer.allocateDirect(MAX_PACKET_BYTES)
  private var closed = false

  /** The OpusHead the TV's decoder is set up with (RFC 7845, section 5.1). */
  val header: ByteArray

  init {
    load()
    val error = IntBuffer.allocate(1)
    encoder = Opus.INSTANCE.opus_encoder_create(SAMPLE_RATE, CHANNELS, Opus.OPUS_APPLICATION_AUDIO, error)
    if (error[0] != Opus.OPUS_OK) throw CaptureException("The Opus encoder didn't start (${error[0]})")
    Opus.INSTANCE.opus_encoder_ctl(encoder, Opus.OPUS_SET_BITRATE_REQUEST, BITRATE)
    val lookahead = IntByReference()
    Opus.INSTANCE.opus_encoder_ctl(encoder, Opus.OPUS_GET_LOOKAHEAD_REQUEST, lookahead)
    header = opusHead(CHANNELS, lookahead.value, SAMPLE_RATE)
  }

  /** Encodes one frame of [FRAME_SIZE] interleaved samples per channel into a new packet. */
  fun encode(pcm: FloatArray): ByteArray {
    output.clear()
    val length = Opus.INSTANCE.opus_encode_float(encoder, pcm, FRAME_SIZE, output, MAX_PACKET_BYTES)
    if (length < 0) throw CaptureException("Opus encoding failed ($length)")
    return ByteArray(length).also { output.get(it) }
  }

  override fun close() {
    if (closed) return
    closed = true
    Opus.INSTANCE.opus_encoder_destroy(encoder)
  }

  companion object {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2

    /** 20 ms. */
    const val FRAME_SIZE = 960
    const val FRAME_US = 20_000L
    private const val BITRATE = 128_000
    private const val MAX_PACKET_BYTES = 4000

    @Volatile private var loaded = false

    private fun load() =
      synchronized(this) {
        if (!loaded) {
          if (!OpusLibrary.loadFromJar()) throw CaptureException("libopus didn't load")
          loaded = true
        }
      }

    internal fun opusHead(channels: Int, preSkip: Int, inputRate: Int): ByteArray =
      ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".encodeToByteArray())
        .put(1)
        .put(channels.toByte())
        .putShort(preSkip.toShort())
        .putInt(inputRate)
        .putShort(0)
        .put(0)
        .array()
  }
}
