package io.github.lumpilabs.beamline.receiver

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test
  fun splitsParameterSetsFromAKeyframe() {
    // As an Apple device sends it: SPS, PPS, SEI and IDR in one buffer.
    val sps = bytes(0, 0, 0, 1, 0x67, 0x42)
    val pps = bytes(0, 0, 0, 1, 0x68, 0xCE)
    val sei = bytes(0, 0, 0, 1, 0x06, 0x05)
    val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84)
    val split = NalUnits.split(sps + pps + sei + idr, h265 = false)
    assertContentEquals(sps + pps, split.parameterSets)
    assertContentEquals(sei + idr, split.picture)
    assertTrue(split.keyframe)
  }

  @Test
  fun aSliceAloneIsNoKeyframeAndHasNoParameterSets() {
    val slice = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x22)
    val split = NalUnits.split(slice, h265 = false)
    assertNull(split.parameterSets)
    assertContentEquals(slice, split.picture)
    assertFalse(split.keyframe)
  }

  @Test
  fun h265CraIsAKeyframe() {
    val vps = bytes(0, 0, 0, 1, 0x40, 1)
    val cra = bytes(0, 0, 1, 0x2A, 1, 0xAF)
    val split = NalUnits.split(vps + cra, h265 = true)
    assertContentEquals(vps, split.parameterSets)
    assertContentEquals(cra, split.picture)
    assertTrue(split.keyframe)
  }
}
