package io.github.lumpilabs.beamline.receiver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecodeLeadTest {
  private val lead = DecodeLead()

  private fun decode(ms: Long, times: Int = 1) = repeat(times) { lead.onDecoded(ms * 1_000_000) }

  private val leadMs get() = lead.ns / 1_000_000.0

  @Test
  fun startsAtTheMinimum() {
    assertEquals(DecodeLead.MIN_NS, lead.ns)
    assertNull(lead.decodeNs)
  }

  @Test
  fun aFastDecoderKeepsTheMinimum() {
    decode(20, times = 200)
    assertEquals(DecodeLead.MIN_NS, lead.ns)
    assertEquals(20_000_000, lead.decodeNs)
  }

  @Test
  fun aSlowDecoderGetsItsFramesEarlierThanItTakes() {
    // HEVC on the Fire TV: about 85 ms per frame.
    decode(85, times = 20)
    assertTrue(leadMs > 95, "lead $leadMs ms")
    assertTrue(lead.ns <= DecodeLead.MAX_NS)
  }

  @Test
  fun oneSlowFrameMovesItOnlyPartOfTheWay() {
    decode(20, times = 50)
    decode(300)
    assertTrue(leadMs in 65.0..75.0, "lead $leadMs ms")
  }

  @Test
  fun itComesDownSlowlyAfterwards() {
    decode(85, times = 20)
    decode(20, times = 10)
    assertTrue(leadMs > 85, "after 10 fast frames: $leadMs ms")
    decode(20, times = 500)
    assertEquals(60.0, leadMs, 0.1)
  }

  @Test
  fun itNeverPassesTheHoldLimit() {
    decode(1_000, times = 100)
    assertTrue(lead.ns <= DecodeLead.MAX_NS)
    assertTrue(leadMs > 99.9, "lead $leadMs ms")
  }

  @Test
  fun theDecodeTimeIsAveraged() {
    decode(80)
    decode(88)
    assertEquals(81_000_000, lead.decodeNs)
  }
}
