package io.github.lumpilabs.beamline.receiver

/**
 * Decides when a video frame appears. With sound, a frame waits for the moment its audio is heard,
 * which keeps picture and sound together. Without sound, frames appear a fixed cushion after the
 * fastest delivery of the last few seconds, which turns Wi-Fi bursts into an even picture.
 *
 * Times are System.nanoTime on the TV; sender timestamps are microseconds on the phone's clock.
 */
class PlaybackClock(private val audioOffsetNs: () -> Long?) {
  /** Smallest arrival-minus-capture time per window, newest last. */
  private val windows = ArrayDeque<Window>()

  @Synchronized
  fun onVideoArrival(ptsUs: Long, arrivalNs: Long = System.nanoTime()) {
    val transitNs = arrivalNs - ptsUs * 1000
    val current = windows.lastOrNull()
    if (current == null || arrivalNs - current.startNs >= WINDOW_NS) {
      windows.addLast(Window(arrivalNs, transitNs))
      while (windows.size > WINDOW_COUNT) windows.removeFirst()
    } else if (transitNs < current.minTransitNs) {
      current.minTransitNs = transitNs
    }
  }

  /** When the frame should appear, or null to show it as soon as it's decoded. */
  @Synchronized
  fun renderTimeNs(ptsUs: Long): Long? {
    val offset = audioOffsetNs() ?: windows.minOfOrNull { it.minTransitNs }?.plus(JITTER_CUSHION_NS) ?: return null
    return ptsUs * 1000 + offset
  }

  private class Window(val startNs: Long, var minTransitNs: Long)

  private companion object {
    const val JITTER_CUSHION_NS = 60_000_000L
    const val WINDOW_NS = 500_000_000L

    /** Three seconds of history: long enough to see the fast deliveries, short enough to follow drift. */
    const val WINDOW_COUNT = 6
  }
}
