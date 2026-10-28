package io.github.lumpilabs.beamline.testsender

import java.io.ByteArrayOutputStream
import java.io.File

/** The packets of an Ogg Opus file (RFC 7845), for sending real audio without an encoder. */
class OggOpus(val header: ByteArray, val packets: List<ByteArray>) {
  val channels: Int get() = header[9].toInt() and 0xFF

  companion object {
    fun read(file: File): OggOpus {
      val bytes = file.readBytes()
      val packets = mutableListOf<ByteArray>()
      val packet = ByteArrayOutputStream()
      var page = 0
      while (page + 27 <= bytes.size) {
        require(String(bytes, page, 4, Charsets.US_ASCII) == "OggS") { "$file is not an Ogg file" }
        val segments = bytes[page + 26].toInt() and 0xFF
        var data = page + 27 + segments
        for (i in 0 until segments) {
          val length = bytes[page + 27 + i].toInt() and 0xFF
          packet.write(bytes, data, length)
          data += length
          // A segment shorter than 255 bytes ends a packet.
          if (length < 255) {
            packets += packet.toByteArray()
            packet.reset()
          }
        }
        page = data
      }
      require(packets.size > 2 && String(packets[0], 0, 8, Charsets.US_ASCII) == "OpusHead") {
        "$file is not an Ogg Opus file"
      }
      // The second packet holds the tags.
      return OggOpus(packets[0], packets.drop(2).filter { it.isNotEmpty() })
    }

    /** Duration of an Opus packet from its TOC byte (RFC 6716, section 3.1). */
    fun durationUs(packet: ByteArray): Long {
      val toc = packet[0].toInt() and 0xFF
      val config = toc shr 3
      val frameUs = when {
        config < 12 -> SILK_FRAME_US[config and 3]
        config < 16 -> HYBRID_FRAME_US[config and 1]
        else -> CELT_FRAME_US[config and 3]
      }
      val frames = when (toc and 3) {
        0 -> 1
        3 -> packet[1].toInt() and 0x3F
        else -> 2
      }
      return frameUs * frames
    }

    private val SILK_FRAME_US = longArrayOf(10_000, 20_000, 40_000, 60_000)
    private val HYBRID_FRAME_US = longArrayOf(10_000, 20_000)
    private val CELT_FRAME_US = longArrayOf(2_500, 5_000, 10_000, 20_000)
  }
}
