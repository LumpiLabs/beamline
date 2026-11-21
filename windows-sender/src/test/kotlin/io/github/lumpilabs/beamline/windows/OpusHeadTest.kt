package io.github.lumpilabs.beamline.windows

import io.github.lumpilabs.beamline.protocol.OpusConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class OpusHeadTest {
  @Test
  fun theTvReadsTheHeaderAndItsPreSkip() {
    val head = OpusEncoder.opusHead(channels = 2, preSkip = 312, inputRate = 48_000)
    assertEquals(19, head.size)
    assertEquals("OpusHead", head.copyOfRange(0, 8).decodeToString())
    assertEquals(1, head[8].toInt())
    assertEquals(2, head[9].toInt())
    val config = OpusConfig.parse(head)
    assertEquals(312 * 1_000_000_000L / 48_000, config.codecDelayNs)
  }
}
