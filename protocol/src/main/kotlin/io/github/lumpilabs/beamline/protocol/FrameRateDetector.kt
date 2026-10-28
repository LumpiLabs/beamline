package io.github.lumpilabs.beamline.protocol

/**
 * Estimates the content frame rate from video timestamps, so the TV can match its refresh rate.
 *
 * Phones compose at 60 to 120 Hz, so 25 fps video arrives with uneven gaps (for example 33 and
 * 42 ms on a 120 Hz screen); the mean over a two-second window still lands on 40 ms. Bursts from
 * player controls appearing push the mean below the 25 fps band, which reads as "not steady".
 */
class FrameRateDetector {
  private val timestamps = ArrayDeque<Long>()

  /** Adds one frame's capture time in microseconds. */
  fun add(ptsUs: Long) {
    if (timestamps.isNotEmpty() && ptsUs <= timestamps.last()) timestamps.clear()
    timestamps.addLast(ptsUs)
    while (timestamps.size > 2 && timestamps.last() - timestamps.first() > WINDOW_US) timestamps.removeFirst()
  }

  /** The steady content rate, or null when the window is too short or the rate isn't a standard one. */
  fun steadyFrameRate(): StandardRate? {
    if (timestamps.size < MIN_FRAMES) return null
    val spanUs = timestamps.last() - timestamps.first()
    if (spanUs < WINDOW_US * 3 / 4) return null
    val fps = (timestamps.size - 1) * 1_000_000.0 / spanUs
    return StandardRate.entries.firstOrNull { fps in it.minFps..it.maxFps }
  }

  fun reset() = timestamps.clear()

  /** Content rates worth matching, with the display refresh rate that shows them evenly. */
  enum class StandardRate(val minFps: Double, val maxFps: Double, val displayHz: Float) {
    FPS_24(23.5, 24.5, 24f),
    FPS_25(24.5, 25.8, 50f),
    FPS_30(29.0, 30.8, 60f),
    FPS_50(48.5, 51.5, 50f),
    FPS_60(57.0, 61.0, 60f),
  }

  private companion object {
    const val WINDOW_US = 2_000_000L
    const val MIN_FRAMES = 30
  }
}
