package io.github.lumpilabs.beamline.receiver

import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackStatsTest {
  private var now = 1_000_000_000L
  private val stats = PlaybackStats { now }

  private fun snapshot() = stats.snapshot(VideoSize(1920, 1080), Codecs.H264, null, null, null, null)

  /** A frame sent at [sentMs] on the phone's clock and arriving [transitMs] later. */
  private fun arrive(sentMs: Long, transitMs: Long, bytes: Int = 10_000) {
    stats.onVideoArrival(sentMs * 1000, bytes, (sentMs + transitMs) * 1_000_000)
  }

  @Test
  fun ratesCoverTheTimeSinceTheLastSnapshot() {
    repeat(25) { arrive(it * 40L, 20) }
    stats.framesShown.addAndGet(24)
    now += 1_000_000_000
    val first = snapshot()
    assertEquals(25.0, first.receivedFps, 0.01)
    assertEquals(24.0, first.shownFps, 0.01)
    assertEquals(2_000, first.bitrateKbps)

    repeat(10) { arrive(1_000 + it * 100L, 20) }
    now += 2_000_000_000
    assertEquals(5.0, snapshot().receivedFps, 0.01)
  }

  @Test
  fun jitterIsTheSpreadOfOneIntervalAndTheWorstIsKept() {
    arrive(0, 20)
    arrive(40, 70)
    now += 1_000_000_000
    val bad = snapshot()
    assertEquals(50, bad.jitterMs)
    assertEquals(50, bad.worstJitterMs)

    arrive(80, 30)
    arrive(120, 40)
    now += 1_000_000_000
    val calm = snapshot()
    assertEquals(10, calm.jitterMs)
    assertEquals(50, calm.worstJitterMs)
  }

  @Test
  fun noFramesMeansNoJitterFigure() {
    now += 1_000_000_000
    assertNull(snapshot().jitterMs)
  }

  @Test
  fun aReconnectCountsAndStartsTheSpreadOver() {
    arrive(0, 10)
    stats.onReconnect()
    // The reconnected sender's timestamps started over, so the transit looks very different.
    arrive(-60_000, 60_030)
    now += 1_000_000_000
    val snapshot = snapshot()
    assertEquals(1, snapshot.reconnects)
    assertEquals(0, snapshot.jitterMs)
  }

  @Test
  fun countsAddUpOverTheWholeSession() {
    stats.framesLate.addAndGet(3)
    stats.framesDropped.addAndGet(12)
    stats.soundSkippedMs.addAndGet(205)
    stats.soundQueuedMs = 312
    now += 61_000_000_000
    val snapshot = stats.snapshot(null, null, null, 342_000_000, 6_000, 4_000_000)
    assertEquals(61_000, snapshot.elapsedMs)
    assertEquals(3, snapshot.framesLate)
    assertEquals(12, snapshot.framesDropped)
    assertEquals(205, snapshot.soundSkippedMs)
    assertEquals(312, snapshot.soundQueuedMs)
    assertEquals(342, snapshot.delayMs)
    assertEquals(6, snapshot.roundTripMs)
    assertEquals(4, snapshot.skewMs)
  }
}
