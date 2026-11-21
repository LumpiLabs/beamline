package io.github.lumpilabs.beamline.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264Test {
  private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28)
  private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
  private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x10)
  private val slice = byteArrayOf(0x41, 0x9A.toByte(), 0x02)

  /** As Media Foundation's encoders write a keyframe: AUD, SPS and PPS, then the IDR slice. */
  private val keyframe =
    byteArrayOf(0, 0, 0, 1, 0x09, 0x10) + byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr

  @Test
  fun aKeyframeHasAnIdrSlice() {
    assertTrue(H264.isKeyframe(keyframe, keyframe.size))
    val delta = byteArrayOf(0, 0, 0, 1) + slice
    assertFalse(H264.isKeyframe(delta, delta.size))
  }

  @Test
  fun theParameterSetsBecomeTheCodecConfig() {
    val config = H264.parameterSets(keyframe, keyframe.size)
    assertContentEquals(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps, config)
  }

  @Test
  fun aFrameWithoutAnSpsHasNoCodecConfig() {
    val delta = byteArrayOf(0, 0, 0, 1) + slice
    assertNull(H264.parameterSets(delta, delta.size))
  }

  @Test
  fun onlyTheGivenLengthCounts() {
    val buffer = keyframe + ByteArray(64)
    assertEquals(4, H264.nalUnits(buffer, keyframe.size).size)
    assertTrue(H264.isKeyframe(buffer, keyframe.size))
  }

  @Test
  fun nalUnitsLeaveOutStartCodes() {
    val units = H264.nalUnits(keyframe, keyframe.size).map { (start, end) -> keyframe.copyOfRange(start, end) }
    assertContentEquals(sps, units[1])
    assertContentEquals(pps, units[2])
    assertContentEquals(idr, units[3])
  }
}
