package io.github.lumpilabs.beamline.receiver

/** Looks into Annex B video (NAL units after 00 00 01 or 00 00 00 01 start codes). */
object NalUnits {
  /**
   * A buffer of video split for the decoder: the parameter sets, which go in as codec config, and
   * the rest. Apple devices send both in one buffer in front of each keyframe.
   */
  class Split(val parameterSets: ByteArray?, val picture: ByteArray?, val keyframe: Boolean)

  /** The NAL unit types in [data], in order: H.264 or, with [h265], H.265 numbering. */
  fun types(data: ByteArray, h265: Boolean): List<Int> = units(data).map { type(data, it, h265) }

  fun split(data: ByteArray, h265: Boolean): Split {
    val units = units(data)
    if (units.isEmpty()) return Split(null, data, keyframe = false)
    val parameterSets = mutableListOf<IntRange>()
    val rest = mutableListOf<IntRange>()
    var keyframe = false
    for (unit in units) {
      val type = type(data, unit, h265)
      if (isParameterSet(type, h265)) parameterSets += unit else rest += unit
      if (isKeyframe(type, h265)) keyframe = true
    }
    return Split(join(data, parameterSets), join(data, rest), keyframe)
  }

  /** Each unit from its start code up to the next one. */
  private fun units(data: ByteArray): List<IntRange> {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 3 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        // A four-byte start code belongs to the unit it starts.
        starts += if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
        i += 3
      } else {
        i++
      }
    }
    return starts.mapIndexed { n, start -> start until (starts.getOrNull(n + 1) ?: data.size) }
  }

  private fun type(data: ByteArray, unit: IntRange, h265: Boolean): Int {
    var header = unit.first
    while (data[header].toInt() == 0) header++
    val byte = data[header + 1].toInt() and 0xFF
    return if (h265) (byte shr 1) and 0x3F else byte and 0x1F
  }

  /** H.264 SPS and PPS; H.265 VPS, SPS and PPS. */
  private fun isParameterSet(type: Int, h265: Boolean): Boolean = if (h265) type in 32..34 else type == 7 || type == 8

  /** H.264 IDR; H.265 IRAP pictures (BLA, IDR, CRA). */
  private fun isKeyframe(type: Int, h265: Boolean): Boolean = if (h265) type in 16..21 else type == 5

  private fun join(data: ByteArray, units: List<IntRange>): ByteArray? {
    if (units.isEmpty()) return null
    val out = ByteArray(units.sumOf { it.last - it.first + 1 })
    var at = 0
    for (unit in units) {
      data.copyInto(out, at, unit.first, unit.last + 1)
      at += unit.last - unit.first + 1
    }
    return out
  }
}
