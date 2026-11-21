package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitrateControllerTest {
  private var nowMs = 0L
  private val controller = BitrateController(9_000_000) { nowMs * 1_000_000 }

  private fun waited(ms: Long) = controller.onLongestWait(ms * 1_000_000)

  @Test
  fun startsAtTheStreamsRate() {
    assertEquals(9_000_000, controller.bps)
    assertNull(waited(5))
  }

  @Test
  fun skippedVideoCutsTheRateHard() {
    assertEquals(5_400_000, controller.onCongested())
  }

  @Test
  fun framesBackingUpLowerItGently() {
    assertEquals(7_200_000, waited(120))
  }

  @Test
  fun waitsInBetweenChangeNothing() {
    controller.onCongested()
    nowMs = 60_000
    assertNull(waited(50))
  }

  @Test
  fun oneStallCutsOnceThoughItShowsSeveralTimes() {
    assertEquals(5_400_000, controller.onCongested())
    nowMs = 300
    assertNull(waited(300), "the next second's wait is the same stall")
    nowMs = 600
    assertNull(controller.onCongested(), "and so is a keyframe skipped while it lasts")
    assertEquals(5_400_000, controller.bps)
  }

  @Test
  fun theDeepestCutCountsWhenTheyComeTogether() {
    assertEquals(7_200_000, waited(120))
    nowMs = 500
    assertEquals(5_400_000, controller.onCongested(), "from 9, not from 7.2 Mbit/s")
  }

  @Test
  fun cutsAddUpASecondApart() {
    controller.onCongested()
    nowMs = 1_000
    assertEquals(3_240_000, controller.onCongested())
  }

  @Test
  fun itNeverGoesBelowAnEighthOrOneMegabit() {
    repeat(20) {
      nowMs += 1_000
      controller.onCongested()
    }
    assertEquals(1_125_000, controller.bps)
    val small = BitrateController(2_000_000) { nowMs * 1_000_000 }
    repeat(20) {
      nowMs += 1_000
      small.onCongested()
    }
    assertEquals(1_000_000, small.bps)
  }

  @Test
  fun itComesBackStepByStepAfterCalm() {
    controller.onCongested()
    nowMs = 5_000
    assertNull(waited(5), "still holding after skipped video")
    nowMs = 10_000
    assertEquals(6_210_000, waited(5))
    nowMs = 11_000
    assertNull(waited(5), "one step every 3 seconds")
    nowMs = 13_000
    assertEquals(7_141_500, waited(5))
  }

  @Test
  fun itRaisesCarefullyNearWhereTheNetworkFellBehind() {
    assertEquals(7_200_000, waited(120), "fell behind at 9 Mbit/s")
    nowMs = 5_000
    assertEquals(7_650_000, waited(5), "a big step, but only up to 85% of 9")
    nowMs = 8_000
    assertEquals(8_032_500, waited(5), "from there 5% at a time")
    nowMs = 12_000
    assertNull(waited(5), "every 5 seconds")
    nowMs = 13_000
    assertEquals(8_434_125, waited(5))
  }

  @Test
  fun bigStepsComeBackWellPastIt() {
    waited(120)
    nowMs = 1_000
    assertEquals(5_760_000, waited(120), "fell behind at 7.2 Mbit/s")
    while (controller.bps <= 8_280_000) {
      nowMs += 5_000
      waited(5)
    }
    assertEquals(8_611_453, controller.bps, "the first rate 15% past 7.2")
    nowMs += 5_000
    assertEquals(9_000_000, waited(5), "a big step again")
  }

  @Test
  fun itNeverGoesAboveTheStreamsRate() {
    waited(120)
    repeat(10) {
      nowMs += 10_000
      waited(5)
    }
    assertEquals(9_000_000, controller.bps)
  }

  @Test
  fun aNewSizeKeepsTheShare() {
    controller.onCongested()
    assertEquals(2_400_000, controller.setMax(4_000_000))
    assertEquals(4_000_000, controller.maxBps)
  }
}
