package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class PairingCodeTest {
  private val receiver = byteArrayOf(1, 2, 3)
  private val sender = byteArrayOf(4, 5, 6)

  @Test
  fun matchesTheSpecTestVector() {
    // Computed independently with .NET SHA-256; see docs/DESIGN.md.
    assertEquals("939459", PairingCode.derive(receiver, sender))
  }

  @Test
  fun dependsOnWhichSideIsWhich() {
    assertEquals("619618", PairingCode.derive(sender, receiver))
  }

  @Test
  fun alwaysHasSixDigits() {
    repeat(200) { i ->
      val code = PairingCode.derive(byteArrayOf(i.toByte()), byteArrayOf((i * 7).toByte(), 1))
      assertEquals(6, code.length)
      assertEquals(code, code.filter(Char::isDigit))
    }
  }
}
