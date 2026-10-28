package io.github.lumpilabs.beamline.receiver

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackClockTest {
  @Test
  fun withoutSoundFramesFollowTheFastestDeliveryPlusACushion() {
    val clock = PlaybackClock { null }
    clock.onVideoArrival(ptsUs = 1_000_000, arrivalNs = 1_050_000_000) // 50 ms in transit
    clock.onVideoArrival(ptsUs = 1_040_000, arrivalNs = 1_070_000_000) // 30 ms in transit
    assertEquals(1_080_000_000L + 30_000_000 + 250_000_000, clock.schedule(1_080_000))
  }

  @Test
  fun withSoundFramesAppearWhenTheirSoundIsHeard() {
    val clock = PlaybackClock { 5_000_000_000 }
    assertEquals(1_000_000_000L + 5_000_000_000, clock.schedule(1_000_000))
  }

  @Test
  fun nothingToGoByMeansShowAtOnce() {
    assertEquals(null, PlaybackClock { null }.schedule(1_000_000))
  }

  @Test
  fun unevenArrivalsAreShownEvenly() {
    // 25 fps composed on a 120 Hz phone: four gaps of 41.7 ms, then one of 33.3 ms.
    val gapsUs = listOf(41_667L, 41_667L, 41_667L, 41_667L, 33_333L)
    val offsetNs = 2_000_000_000L
    val clock = PlaybackClock { offsetNs }
    var ptsUs = 0L
    val renders = mutableListOf<Long>()
    repeat(200) {
      renders += clock.schedule(ptsUs)!!
      assertTrue(abs(renders.last() - (ptsUs * 1000 + offsetNs)) < 40_000_000, "Strayed from the sound")
      ptsUs += gapsUs[it % gapsUs.size]
    }
    // After a second to settle, every frame stays close to 40 ms; the raw gaps vary by 8 ms.
    val shownGaps = renders.zipWithNext { a, b -> b - a }.drop(25)
    assertTrue(shownGaps.all { it in 37_500_000..42_500_000 }, "Uneven: ${shownGaps.minOrNull()}..${shownGaps.maxOrNull()}")
  }

  @Test
  fun aStillScreenStartsANewCadence() {
    val offsetNs = 2_000_000_000L
    val clock = PlaybackClock { offsetNs }
    clock.schedule(0)
    clock.schedule(40_000)
    // Half a second without frames: the next one goes exactly by its timestamp again.
    assertEquals(540_000_000L + offsetNs, clock.schedule(540_000))
  }
}
