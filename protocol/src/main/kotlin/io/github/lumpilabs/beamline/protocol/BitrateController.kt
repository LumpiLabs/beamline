package io.github.lumpilabs.beamline.protocol

/**
 * Picks the video bitrate from how long frames wait to be sent ([SendQueue]). It goes down at once
 * when they back up and comes back a step at a time after calm stretches, carefully near the rate
 * where they backed up, so on a weak Wi-Fi the picture gets softer instead of stuttering. The highest rate is [maxBps], the encoder's rate for
 * the stream; the lowest an eighth of it, but at least [MIN_BPS].
 *
 * [nanoTime] is replaceable for tests.
 */
class BitrateController(maxBps: Int, private val nanoTime: () -> Long = System::nanoTime) {
  var maxBps = maxBps
    private set

  var bps = maxBps
    private set

  private val minBps get() = maxOf(MIN_BPS, maxBps / 8).coerceAtMost(maxBps)

  /** No raising before this time. */
  private var holdUntilNs = Long.MIN_VALUE

  /** Cuts until this time are taken from [cutFromBps], not from each other: see [lower]. */
  private var settleUntilNs = Long.MIN_VALUE
  private var cutFromBps = maxBps

  /** The rate at which the network last fell behind, until raising gets well past it: see [raise]. */
  private var troubleBps: Int? = null

  /** Video had to be skipped. Returns the new bitrate, or null if it stays. */
  fun onCongested(): Int? = lower(CONGESTED_PERCENT, HOLD_AFTER_CONGESTION_NS)

  /**
   * About once a second, with the longest a video frame waited to be sent since the last call.
   * Returns the new bitrate, or null if it stays.
   */
  fun onLongestWait(waitNs: Long): Int? =
    when {
      waitNs > BACKED_UP_NS -> lower(BACKED_UP_PERCENT, HOLD_AFTER_BACKED_UP_NS)
      waitNs < CALM_NS && nanoTime() >= holdUntilNs -> raise()
      else -> null
    }

  /** The stream restarted with another maximum (a new size): keeps the same share of it. */
  fun setMax(newMaxBps: Int): Int {
    val scaled = (newMaxBps.toLong() * bps / maxBps).toInt()
    maxBps = newMaxBps
    bps = scaled.coerceIn(minBps, maxBps)
    settleUntilNs = Long.MIN_VALUE
    return bps
  }

  /**
   * One stall shows up several times: as skipped video, in the next second's waits, and again in
   * the keyframes sent while it lasts. Cuts within [SETTLE_NS] of each other therefore don't add
   * up: each is taken from the rate before the first, and the deepest counts.
   */
  private fun lower(percent: Int, holdNs: Long): Int? {
    val now = nanoTime()
    if (now >= settleUntilNs) cutFromBps = bps
    holdUntilNs = maxOf(holdUntilNs, now + holdNs)
    val next = percentOf(cutFromBps, percent).coerceAtLeast(minBps)
    if (next >= bps) return null
    settleUntilNs = now + SETTLE_NS
    troubleBps = cutFromBps
    return change(next)
  }

  /**
   * Up a big step every few seconds, but from [CAREFUL_FROM_PERCENT] of the rate at which the
   * network last fell behind only a small one now and then: the next big step there would likely
   * be one too many, and each one costs a backlog the TV may show as a stutter. Once well past
   * that rate, the network has room again and the big steps come back.
   */
  private fun raise(): Int? {
    val trouble = troubleBps
    val carefulFrom = trouble?.let { percentOf(it, CAREFUL_FROM_PERCENT) }
    val careful = carefulFrom != null && bps >= carefulFrom
    holdUntilNs = nanoTime() + if (careful) CAREFUL_RAISE_EVERY_NS else RAISE_EVERY_NS
    var next = percentOf(bps, if (careful) CAREFUL_RAISE_PERCENT else RAISE_PERCENT)
    if (!careful && carefulFrom != null) next = next.coerceAtMost(carefulFrom)
    if (trouble != null && next > percentOf(trouble, PAST_TROUBLE_PERCENT)) troubleBps = null
    return change(next.coerceAtMost(maxBps))
  }

  private fun percentOf(rate: Int, percent: Int): Int = (rate.toLong() * percent / 100).toInt()

  private fun change(next: Int): Int? {
    if (next == bps) return null
    bps = next
    return next
  }

  companion object {
    /** Below this, screen text gets hard to read even when nothing moves. */
    const val MIN_BPS = 1_000_000

    const val CONGESTED_PERCENT = 60
    const val BACKED_UP_PERCENT = 80
    const val RAISE_PERCENT = 115
    const val CAREFUL_FROM_PERCENT = 85
    const val CAREFUL_RAISE_PERCENT = 105
    const val PAST_TROUBLE_PERCENT = 115

    /** Frames waited this long: the network isn't keeping up, though nothing was skipped yet. */
    const val BACKED_UP_NS = 80_000_000L

    /** Frames went out at once: there may be room for more. */
    const val CALM_NS = 20_000_000L

    /** At most one cut a second: enough for the encoder's new rate to show in the send queue. */
    const val SETTLE_NS = 1_000_000_000L

    const val HOLD_AFTER_CONGESTION_NS = 10_000_000_000L
    const val HOLD_AFTER_BACKED_UP_NS = 5_000_000_000L
    const val RAISE_EVERY_NS = 3_000_000_000L
    const val CAREFUL_RAISE_EVERY_NS = 5_000_000_000L
  }
}
