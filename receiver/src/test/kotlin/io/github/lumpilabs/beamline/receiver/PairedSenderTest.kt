package io.github.lumpilabs.beamline.receiver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairedSenderTest {
  @Test
  fun survivesStoring() {
    val sender = PairedSender("ab12", "S26+ von Jaci", 1_761_990_000_000, 1_761_993_600_000)
    assertEquals(sender, PairedSender.decode("ab12", sender.encode()))
  }

  @Test
  fun aNameMayContainTheSeparator() {
    val sender = PairedSender("ab12", "Phone; living room", 1, 2)
    assertEquals("Phone; living room", PairedSender.decode("ab12", sender.encode())?.name)
  }

  @Test
  fun anUnreadableValueIsSkipped() {
    assertNull(PairedSender.decode("ab12", "not;a number;Phone"))
    assertNull(PairedSender.decode("ab12", "12"))
  }
}
