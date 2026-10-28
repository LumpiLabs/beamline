package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockSyncTest {
  @Test
  fun unknownBeforeTheFirstPong() {
    val sync = ClockSync()
    assertNull(sync.offsetUs)
    assertNull(sync.roundTripUs)
  }

  @Test
  fun aSymmetricRoundTripGivesTheExactOffset() {
    val sync = ClockSync()
    // The peer's clock is 4 ms behind; the pong is answered halfway through a 200 µs round trip.
    sync.add(sentUs = 10_000, receivedUs = 10_200, peerUs = 6_100)
    assertEquals(4_000, sync.offsetUs)
    assertEquals(200, sync.roundTripUs)
  }

  @Test
  fun trustsTheShortestRoundTrip() {
    val sync = ClockSync()
    sync.add(sentUs = 0, receivedUs = 100, peerUs = -3_950)
    // This pong waited 20 ms behind a keyframe before it was sent back.
    sync.add(sentUs = 1_000_000, receivedUs = 1_030_000, peerUs = 1_000_000 + 25_000 - 4_000)
    assertEquals(4_000, sync.offsetUs)
    assertEquals(30_000, sync.roundTripUs)
  }

  @Test
  fun forgetsOldRoundTrips() {
    val sync = ClockSync()
    sync.add(sentUs = 0, receivedUs = 100, peerUs = 50)
    repeat(10) {
      val sent = (it + 1) * 1_000_000L
      sync.add(sentUs = sent, receivedUs = sent + 2_000, peerUs = sent + 1_000 - 7_000)
    }
    assertEquals(7_000, sync.offsetUs)
  }

  @Test
  fun ignoresAPongFromBeforeItsPing() {
    val sync = ClockSync()
    sync.add(sentUs = 5_000, receivedUs = 4_000, peerUs = 0)
    assertNull(sync.offsetUs)
  }
}
