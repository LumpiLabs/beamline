package io.github.lumpilabs.beamline.receiver

import io.github.lumpilabs.beamline.protocol.FrameRateDetector
import kotlin.math.abs

/**
 * Decides when each video frame appears. The aim is smooth motion, not the lowest delay:
 *
 * - With sound, a frame appears when its sound is heard, so picture and sound stay together.
 *   The sound itself sits in a buffer of a few hundred milliseconds, which absorbs Wi-Fi bursts.
 * - Without sound, frames appear [VIDEO_ONLY_DELAY_NS] after the fastest delivery of the last few
 *   seconds.
 * - Frames are spaced evenly: phones compose at 60 to 120 Hz, so 25 fps video arrives with uneven
 *   gaps (33 and 42 ms). The schedule follows the average spacing and only slowly corrects
 *   towards the exact timestamps.
 *
 * Times are System.nanoTime on the TV; sender timestamps are microseconds on the phone's clock.
 */
class PlaybackClock(private val audioOffsetNs: () -> Long?) {
  private val windows = ArrayDeque<Window>()
  private val frameRate = FrameRateDetector()
  private var lastPtsUs: Long? = null
  private var lastRenderNs: Long? = null
  private var intervalNs: Double? = null

  @Synchronized
  fun onVideoArrival(ptsUs: Long, arrivalNs: Long = System.nanoTime()) {
    frameRate.add(ptsUs)
    val transitNs = arrivalNs - ptsUs * 1000
    val current = windows.lastOrNull()
    if (current == null || arrivalNs - current.startNs >= WINDOW_NS) {
      windows.addLast(Window(arrivalNs, transitNs))
      while (windows.size > WINDOW_COUNT) windows.removeFirst()
    } else if (transitNs < current.minTransitNs) {
      current.minTransitNs = transitNs
    }
  }

  /** The content's steady frame rate, for matching the TV's refresh rate. */
  @Synchronized
  fun steadyFrameRate(): FrameRateDetector.StandardRate? = frameRate.steadyFrameRate()

  /**
   * When the frame should appear, or null to show it as soon as it's decoded. Call once per frame,
   * in arrival order: the even spacing depends on the previous frame.
   */
  @Synchronized
  fun schedule(ptsUs: Long): Long? {
    val offset = audioOffsetNs()
      ?: windows.minOfOrNull { it.minTransitNs }?.plus(VIDEO_ONLY_DELAY_NS)
      ?: return null
    val ideal = ptsUs * 1000 + offset
    val previousPts = lastPtsUs
    val previousRender = lastRenderNs
    lastPtsUs = ptsUs

    val gapNs = previousPts?.let { (ptsUs - it) * 1000 }
    if (previousRender == null || gapNs == null || gapNs <= 0 || gapNs > MAX_CADENCE_GAP_NS) {
      // First frame, or a pause in the content: start a new cadence.
      intervalNs = null
      lastRenderNs = ideal
      return ideal
    }
    val interval = intervalNs?.let { it + (gapNs - it) / 16 } ?: gapNs.toDouble()
    intervalNs = interval
    val predicted = previousRender + interval.toLong()
    val error = ideal - predicted
    val render = if (abs(error) < interval) predicted + error / 16 else ideal
    lastRenderNs = render
    return render
  }

  private class Window(val startNs: Long, var minTransitNs: Long)

  private companion object {
    const val VIDEO_ONLY_DELAY_NS = 250_000_000L
    const val WINDOW_NS = 500_000_000L

    /** Three seconds of history: long enough to see the fast deliveries, short enough to follow drift. */
    const val WINDOW_COUNT = 6

    /** Longer gaps mean the screen was still; the next frame starts a new cadence. */
    const val MAX_CADENCE_GAP_NS = 100_000_000L
  }
}
