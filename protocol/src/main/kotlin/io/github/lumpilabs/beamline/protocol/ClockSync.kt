package io.github.lumpilabs.beamline.protocol

/**
 * Estimates how far a peer's clock is from this device's, from [Ping]/[Pong] round trips. The TV
 * uses it to tell how far its picture is behind the phone's screen.
 *
 * A pong sent back at once arrives halfway through the round trip; one that waited behind a large
 * video frame arrives later. So of the last [WINDOW] round trips, the shortest one is trusted.
 */
class ClockSync {
  private val samples = ArrayDeque<Sample>()

  /** The latest round trip in microseconds, or null before the first pong. */
  val roundTripUs: Long?
    get() = samples.lastOrNull()?.roundTripUs

  /** This device's clock minus the peer's, in microseconds, or null before the first pong. */
  val offsetUs: Long?
    get() = samples.minByOrNull { it.roundTripUs }?.offsetUs

  /**
   * Adds one round trip: the ping went out at [sentUs] and the pong came back at [receivedUs], both
   * on this device's clock; [peerUs] is the peer's time in the pong.
   */
  fun add(sentUs: Long, receivedUs: Long, peerUs: Long) {
    val roundTripUs = receivedUs - sentUs
    if (roundTripUs < 0) return
    samples.addLast(Sample(roundTripUs, sentUs + roundTripUs / 2 - peerUs))
    while (samples.size > WINDOW) samples.removeFirst()
  }

  fun reset() = samples.clear()

  private class Sample(val roundTripUs: Long, val offsetUs: Long)

  private companion object {
    /** Ten seconds at one ping a second: enough to catch a quick round trip, short enough to follow drift. */
    const val WINDOW = 10
  }
}
