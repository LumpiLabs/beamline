package io.github.lumpilabs.beamline.receiver

/** Looks into Annex B video (NAL units after 00 00 01 or 00 00 00 01 start codes). */
object NalUnits {
  /** The NAL unit types in [data], in order: H.264 or, with [h265], H.265 numbering. */
  fun types(data: ByteArray, h265: Boolean): List<Int> {
    val types = mutableListOf<Int>()
    var i = 0
    while (i + 3 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        val header = data[i + 3].toInt() and 0xFF
        types += if (h265) (header shr 1) and 0x3F else header and 0x1F
        i += 3
      } else {
        i++
      }
    }
    return types
  }
}
