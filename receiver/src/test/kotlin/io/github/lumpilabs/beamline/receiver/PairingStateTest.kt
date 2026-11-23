package io.github.lumpilabs.beamline.receiver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingStateTest {
  @Test
  fun aNewPhoneWaitsForOkUntilItWasPressed() {
    val asking = ReceiverState.Pairing("Pixel 8 Pro", "478860", mustAllow = true)
    assertTrue(asking.waitsForOk)
    assertFalse(asking.copy(allowed = true).waitsForOk)
  }

  @Test
  fun aKnownPhoneNeverWaitsForOk() {
    assertFalse(ReceiverState.Pairing("Pixel 8 Pro", "478860", mustAllow = false).waitsForOk)
  }
}
