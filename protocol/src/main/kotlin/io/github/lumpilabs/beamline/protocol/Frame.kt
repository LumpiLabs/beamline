package io.github.lumpilabs.beamline.protocol

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Thrown when the peer sends bytes that don't follow the protocol. */
class ProtocolException(message: String) : IOException(message)

enum class FrameType(val code: Int) {
  CONTROL(0x01),
  VIDEO(0x02),
  AUDIO(0x03);

  companion object {
    fun fromCode(code: Int): FrameType =
      entries.firstOrNull { it.code == code }
        ?: throw ProtocolException("Unknown frame type 0x%02x".format(code))
  }
}

object FrameFlags {
  /** Video only: the frame can be decoded without earlier frames. */
  const val KEYFRAME = 0x01

  /** Video and audio: the body is codec configuration (for example SPS/PPS), not media. */
  const val CODEC_CONFIG = 0x02
}

/**
 * One unit on the wire: `u8 type, u8 flags, u32 length, u64 pts_us, body`, big-endian.
 *
 * [ptsUs] is the sender's monotonic capture time in microseconds, or 0 for control frames.
 */
class Frame(val type: FrameType, val flags: Int, val ptsUs: Long, val body: ByteArray) {
  fun hasFlag(flag: Int): Boolean = flags and flag != 0

  companion object {
    const val HEADER_SIZE = 14

    /** Upper bound for a single body, so a corrupt length can't exhaust memory. */
    const val MAX_BODY_SIZE = 8 * 1024 * 1024
  }
}

/** Writes frames to a stream. Safe to call from several threads, for example video and audio. */
class FrameWriter(output: OutputStream) {
  private val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))

  fun write(frame: Frame) = write(frame.type, frame.flags, frame.ptsUs, frame.body, frame.body.size)

  /**
   * Writes a frame whose body is the first [length] bytes of [body]. The bytes are sent (or
   * buffered) before this returns, so a sender can reuse one array for every frame instead of
   * allocating a new one each time.
   */
  @Synchronized
  fun write(type: FrameType, flags: Int, ptsUs: Long, body: ByteArray, length: Int) {
    require(length in 0..body.size) { "Length $length outside the body's ${body.size} bytes" }
    require(length <= Frame.MAX_BODY_SIZE) { "Frame body of $length bytes exceeds ${Frame.MAX_BODY_SIZE}" }
    out.writeByte(type.code)
    out.writeByte(flags)
    out.writeInt(length)
    out.writeLong(ptsUs)
    out.write(body, 0, length)
    out.flush()
  }
}

/** Reads frames from a stream. Not thread-safe; use one reader thread per connection. */
class FrameReader(input: InputStream) {
  private val input = DataInputStream(input.buffered(64 * 1024))

  /** Returns the next frame, or null if the stream ended cleanly between two frames. */
  fun read(): Frame? {
    val typeCode = input.read()
    if (typeCode < 0) return null
    try {
      val type = FrameType.fromCode(typeCode)
      val flags = input.readUnsignedByte()
      val length = input.readInt()
      if (length < 0 || length > Frame.MAX_BODY_SIZE) {
        throw ProtocolException("Frame body of $length bytes is outside 0..${Frame.MAX_BODY_SIZE}")
      }
      val ptsUs = input.readLong()
      val body = ByteArray(length)
      input.readFully(body)
      return Frame(type, flags, ptsUs, body)
    } catch (_: EOFException) {
      throw ProtocolException("Stream ended inside a frame")
    }
  }
}
