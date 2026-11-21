package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class H264SpsTest {
  /** From a Galaxy S26+ (screenrecord, 1080 x 2340): High profile, VUI with a bitstream restriction. */
  private val galaxy = hex("67 64 00 34 ac b4 02 20 09 3f 2c f3 50 10 10 10 78 b1 55")

  /** The same, but saying frames may be reordered by 2 and need 4 frames of buffer. */
  private val galaxyReordering = hex("67 64 00 34 ac b4 02 20 09 3f 2c f3 50 10 10 10 78 b1 4c b0")

  /** Baseline, 1280 x 720, no VUI at all, as simple encoders write it. */
  private val baseline = hex("67 42 c0 1f da 01 40 16 e4")

  private val startCode = hex("00 00 00 01")
  private val pps = hex("68 ee 0d 8b")
  private val slice = hex("65 b8 4f 0f 5c 0b df f8")

  @Test
  fun readsARealSps() {
    val fields = H264Sps.parse(galaxy)
    assertEquals(100, fields.profile)
    assertEquals(1L, fields.maxNumRefFrames)
    assertEquals(68L, fields.widthInMbs)
    assertEquals(147L, fields.heightInMapUnits)
    assertEquals(0L, fields.maxNumReorderFrames)
    assertEquals(1L, fields.maxDecFrameBuffering)
  }

  @Test
  fun anSpsThatAlreadySaysSoIsLeftAlone() {
    val keyframe = startCode + galaxy + startCode + pps + startCode + slice
    assertSame(keyframe, H264Sps.withoutReordering(keyframe))
  }

  @Test
  fun anExistingRestrictionIsReplaced() {
    assertEquals(2L, H264Sps.parse(galaxyReordering).maxNumReorderFrames)
    assertEquals(4L, H264Sps.parse(galaxyReordering).maxDecFrameBuffering)
    val config = startCode + galaxyReordering + startCode + pps
    assertContentEquals(startCode + galaxy + startCode + pps, H264Sps.withoutReordering(config))
  }

  @Test
  fun aVuiIsAddedWhereThereIsNone() {
    assertNull(H264Sps.parse(baseline).maxNumReorderFrames)
    val rewritten = H264Sps.withoutReordering(startCode + baseline + startCode + pps)
    val sps = rewritten.copyOfRange(startCode.size, rewritten.size - startCode.size - pps.size)
    val fields = H264Sps.parse(sps)
    assertEquals(66, fields.profile)
    assertEquals(80L, fields.widthInMbs)
    assertEquals(45L, fields.heightInMapUnits)
    assertEquals(0L, fields.maxNumReorderFrames)
    assertEquals(1L, fields.maxDecFrameBuffering)
    assertContentEquals(startCode + pps, rewritten.copyOfRange(rewritten.size - startCode.size - pps.size, rewritten.size))
    assertContentEquals(rewritten, H264Sps.withoutReordering(rewritten), "rewriting again changes nothing")
  }

  @Test
  fun framesWithoutAnSpsAreLeftAlone() {
    val frame = startCode + slice
    assertSame(frame, H264Sps.withoutReordering(frame))
  }

  @Test
  fun anSpsAfterTheFirstSliceIsNotLookedFor() {
    val frame = startCode + slice + startCode + baseline
    assertSame(frame, H264Sps.withoutReordering(frame))
  }

  @Test
  fun aBrokenSpsIsKept() {
    val config = startCode + hex("67 64 00") + startCode + pps
    assertSame(config, H264Sps.withoutReordering(config))
  }

  private fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
