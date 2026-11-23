package io.github.lumpilabs.beamline.receiver

import kotlin.test.Test
import kotlin.test.assertEquals

class NalUnitsTest {
  private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

  @Test
  fun findsH264TypesAfterBothStartCodes() {
    // SPS (7), PPS (8) and an IDR slice (5); the first two after 4-byte start codes.
    val data = bytes(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE, 0, 0, 1, 0x65, 0x88, 0x84)
    assertEquals(listOf(7, 8, 5), NalUnits.types(data, h265 = false))
  }

  @Test
  fun findsH265Types() {
    // VPS (32), SPS (33), PPS (34), IDR_W_RADL (19): the type sits in bits 1 to 6 of the first byte.
    val data = bytes(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1, 0, 0, 0, 1, 0x26, 1)
    assertEquals(listOf(32, 33, 34, 19), NalUnits.types(data, h265 = true))
  }

  @Test
  fun noStartCodeNoTypes() {
    assertEquals(emptyList(), NalUnits.types(bytes(1, 2, 3, 4, 5), h265 = false))
  }
}
