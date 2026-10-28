package io.github.lumpilabs.beamline.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus decoder setup as Android's MediaCodec expects it: the OpusHead header (`csd-0`), the codec
 * delay (`csd-1`) and the seek pre-roll (`csd-2`), both in nanoseconds.
 *
 * Android's Opus encoder emits its codec config as tagged blocks (`AOPUSHDR`, `AOPUSDLY`,
 * `AOPUSPRL`, each followed by a little-endian u64 length). Android 10 decoders understand that
 * form directly; older ones, like Fire OS 7, need the three parts separately.
 */
class OpusConfig(val header: ByteArray, val codecDelayNs: Long, val seekPreRollNs: Long) {
  companion object {
    private const val SAMPLE_RATE = 48_000
    private const val DEFAULT_SEEK_PRE_ROLL_NS = 80_000_000L

    fun parse(config: ByteArray): OpusConfig = when {
      config.startsWith("AOPUSHDR") -> parseTagged(config)
      config.startsWith("OpusHead") -> OpusConfig(config, preSkipNs(config), DEFAULT_SEEK_PRE_ROLL_NS)
      else -> throw ProtocolException("Unknown Opus codec config")
    }

    private fun parseTagged(config: ByteArray): OpusConfig {
      val buffer = ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN)
      var header: ByteArray? = null
      var delayNs: Long? = null
      var preRollNs: Long? = null
      while (buffer.remaining() >= 16) {
        val tag = ByteArray(8).also { buffer.get(it) }.decodeToString()
        val length = buffer.long
        if (length < 0 || length > buffer.remaining()) throw ProtocolException("Truncated Opus config block $tag")
        val payload = ByteArray(length.toInt()).also { buffer.get(it) }
        when (tag) {
          "AOPUSHDR" -> header = payload
          "AOPUSDLY" -> delayNs = payload.littleEndianLong()
          "AOPUSPRL" -> preRollNs = payload.littleEndianLong()
        }
      }
      val head = header?.takeIf { it.startsWith("OpusHead") } ?: throw ProtocolException("Opus config has no OpusHead")
      return OpusConfig(head, delayNs ?: preSkipNs(head), preRollNs ?: DEFAULT_SEEK_PRE_ROLL_NS)
    }

    /** OpusHead stores the pre-skip as a little-endian u16 at offset 10, in 48 kHz samples. */
    private fun preSkipNs(head: ByteArray): Long {
      if (head.size < 12) throw ProtocolException("OpusHead is too short")
      val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
      return preSkip * 1_000_000_000L / SAMPLE_RATE
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
      size >= prefix.length && copyOfRange(0, prefix.length).decodeToString() == prefix

    private fun ByteArray.littleEndianLong(): Long {
      if (size < 8) throw ProtocolException("Opus config value is too short")
      return ByteBuffer.wrap(this, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }
  }
}
