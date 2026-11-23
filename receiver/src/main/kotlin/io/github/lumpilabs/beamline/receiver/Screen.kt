package io.github.lumpilabs.beamline.receiver

/**
 * Who is on the TV's screen: one sender at a time, a phone or laptop over the Beamline protocol
 * ([MirrorServer]) or an Apple device over AirPlay ([AirPlayReceiver]). Both servers decide with
 * [Admission] against the same occupant, so the busy and take-over rules are the same for all.
 */
class Screen {
  /** A session that has the screen. */
  interface Occupant {
    /** Stays the same when the same device comes back: a phone's `sender_id`, "airplay:" and an Apple device's ID. */
    val senderId: String?

    /** Ends the session, telling the sender [byeReason] where its protocol can. Doesn't block. */
    fun end(byeReason: String)

    /** Drops the connection without a word. */
    fun close()

    /** Returns false when the session is still running after a few seconds. */
    fun awaitEnd(): Boolean
  }

  /** Guards [occupant]. */
  val lock = Any()

  /** Guarded by [lock]. */
  var occupant: Occupant? = null

  /** Ends whatever is on screen, if anything. Doesn't block. */
  fun end(byeReason: String) {
    synchronized(lock) { occupant }?.end(byeReason)
  }

  /** Frees the screen if [session] still has it; returns whether it did, so its end means idle. */
  fun release(session: Occupant): Boolean =
    synchronized(lock) {
      if (occupant !== session) return false
      occupant = null
      true
    }

  /**
   * Waits for a session that was told to end, or drops it if it hangs, so the next one gets the
   * decoder and the screen.
   */
  fun awaitGone(previous: Occupant) {
    if (!previous.awaitEnd()) {
      previous.close()
      previous.awaitEnd()
    }
  }
}
