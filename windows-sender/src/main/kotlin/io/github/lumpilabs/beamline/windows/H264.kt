package io.github.lumpilabs.beamline.windows

import java.io.ByteArrayOutputStream

/** Just enough of H.264's Annex-B byte stream to find keyframes and parameter sets. */
object H264 {
  private const val IDR = 5
  private const val SPS = 7
  private const val PPS = 8

  fun isKeyframe(data: ByteArray, length: Int): Boolean = nalUnits(data, length).any { (start, _) -> type(data, start) == IDR }

  /** The SPS and PPS in [data], each after a 4-byte start code, as the TV takes its codec config; null without an SPS. */
  fun parameterSets(data: ByteArray, length: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    var sps = false
    for ((start, end) in nalUnits(data, length)) {
      val type = type(data, start)
      if (type == SPS || type == PPS) {
        sps = sps || type == SPS
        out.write(START_CODE)
        out.write(data, start, end - start)
      }
    }
    return if (sps) out.toByteArray() else null
  }

  private fun type(data: ByteArray, start: Int): Int = data[start].toInt() and 0x1F

  /** Start and end of each NAL unit, without its start code. */
  internal fun nalUnits(data: ByteArray, length: Int): List<Pair<Int, Int>> {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 2 < length) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        starts += i + 3
        i += 3
      } else {
        i++
      }
    }
    return starts.mapIndexed { n, start ->
      var end = if (n + 1 < starts.size) starts[n + 1] - 3 else length
      // The zero of a 4-byte start code belongs to the next one.
      while (end > start && data[end - 1].toInt() == 0) end--
      start to end
    }.filter { (start, end) -> end > start }
  }

  private val START_CODE = byteArrayOf(0, 0, 0, 1)
}
