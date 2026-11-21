package io.github.lumpilabs.beamline.receiver

import kotlin.test.Test
import kotlin.test.assertEquals

class AdmissionTest {
  private fun decide(
    onScreen: Boolean = true,
    sameSender: Boolean = false,
    pairing: Boolean = false,
    reconnecting: Boolean = false,
    takeOverAllowed: Boolean = false,
  ) = Admission.decide(onScreen, sameSender, pairing, reconnecting, takeOverAllowed)

  @Test
  fun aFreeTvLetsAnyoneIn() {
    assertEquals(Admission.START, decide(onScreen = false))
    assertEquals(Admission.START, decide(onScreen = false, pairing = true, reconnecting = true))
  }

  @Test
  fun theSenderOnScreenTakesItsOwnPlaceBack() {
    assertEquals(Admission.RECONNECT, decide(sameSender = true, reconnecting = true))
    assertEquals(Admission.RECONNECT, decide(sameSender = true, takeOverAllowed = true))
  }

  @Test
  fun anotherSenderIsTurnedAwayAsBusy() {
    assertEquals(Admission.BUSY, decide())
    assertEquals(Admission.BUSY, decide(reconnecting = true))
  }

  @Test
  fun anotherSenderTakesOverWhereAllowed() {
    assertEquals(Admission.TAKE_OVER, decide(takeOverAllowed = true))
  }

  @Test
  fun pairingWaitsForAFreeTvEvenWhereTakingOverIsAllowed() {
    assertEquals(Admission.BUSY, decide(pairing = true, takeOverAllowed = true))
  }

  /** Otherwise a phone back from a Wi-Fi hiccup would push out the one that took over meanwhile. */
  @Test
  fun aReconnectingSenderDoesntTakeOver() {
    val admission = decide(reconnecting = true, takeOverAllowed = true)
    assertEquals(Admission.REPLACED, admission)
    assertEquals("replaced", admission.refusal)
  }
}
