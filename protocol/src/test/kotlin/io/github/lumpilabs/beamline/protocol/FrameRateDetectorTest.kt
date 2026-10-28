package io.github.lumpilabs.beamline.protocol

import io.github.lumpilabs.beamline.protocol.FrameRateDetector.StandardRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameRateDetectorTest {
  private fun detect(intervalsUs: List<Long>): StandardRate? {
    val detector = FrameRateDetector()
    var pts = 1_000_000L
    for (interval in intervalsUs) {
      detector.add(pts)
      pts += interval
    }
    return detector.steadyFrameRate()
  }

  private fun repeatPattern(pattern: List<Long>, seconds: Int): List<Long> {
    val result = mutableListOf<Long>()
    while (result.sum() < seconds * 1_000_000L) result += pattern
    return result
  }

  @Test
  fun recognises25FpsComposedOnA120HzPhone() {
    // 4.8 vsyncs per frame at 120 Hz: four gaps of 41.7 ms and one of 33.3 ms.
    val pattern = listOf(41_667L, 41_667L, 41_667L, 41_667L, 33_333L)
    assertEquals(StandardRate.FPS_25, detect(repeatPattern(pattern, 3)))
  }

  @Test
  fun recognises25FpsComposedOnA60HzPhone() {
    val pattern = listOf(50_000L, 33_333L, 33_333L, 50_000L, 33_333L)
    assertEquals(StandardRate.FPS_25, detect(repeatPattern(pattern, 3)))
  }

  @Test
  fun recognisesSixtyAndFifty() {
    assertEquals(StandardRate.FPS_60, detect(repeatPattern(listOf(16_667L), 3)))
    assertEquals(StandardRate.FPS_50, detect(repeatPattern(listOf(20_000L), 3)))
  }

  @Test
  fun needsEnoughHistory() {
    assertNull(detect(repeatPattern(listOf(40_000L), 1)))
  }

  @Test
  fun nonStandardRatesAreNotMatched() {
    assertNull(detect(repeatPattern(listOf(27_000L), 3)))
  }

  @Test
  fun restartsWhenTimestampsJumpBack() {
    val detector = FrameRateDetector()
    repeat(80) { detector.add(1_000_000L + it * 40_000L) }
    detector.add(500L)
    assertNull(detector.steadyFrameRate())
  }
}
