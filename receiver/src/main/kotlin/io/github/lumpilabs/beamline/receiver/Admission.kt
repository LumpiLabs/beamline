package io.github.lumpilabs.beamline.receiver

import io.github.lumpilabs.beamline.protocol.ByeReasons

/** What the TV does with a sender that says hello, depending on who is on screen. */
enum class Admission(
  /** The `bye` reason that turns the sender away, or null when it gets the screen. */
  val refusal: String?,
) {
  /** Nothing is on screen. */
  START(null),

  /** The same sender came back after losing its connection: its new connection replaces the old one. */
  RECONNECT(null),

  /** The TV lets senders take over: the one on screen gets `bye` "replaced" and this one follows. */
  TAKE_OVER(null),

  /** Another sender is on screen. */
  BUSY(ByeReasons.BUSY),

  /** Another sender is on screen, and this one was only reconnecting: it doesn't push that one out. */
  REPLACED(ByeReasons.REPLACED),
  ;

  companion object {
    /**
     * [sameSender]: the one on screen has this sender's id. [pairing]: the sender has to pair
     * first, which needs the TV's screen, so it waits until the TV is free. [reconnecting]: see
     * `Hello.reconnect`. [takeOverAllowed]: the TV's setting.
     */
    fun decide(
      onScreen: Boolean,
      sameSender: Boolean,
      pairing: Boolean,
      reconnecting: Boolean,
      takeOverAllowed: Boolean,
    ): Admission =
      when {
        !onScreen -> START
        sameSender -> RECONNECT
        pairing || !takeOverAllowed -> BUSY
        reconnecting -> REPLACED
        else -> TAKE_OVER
      }
  }
}
