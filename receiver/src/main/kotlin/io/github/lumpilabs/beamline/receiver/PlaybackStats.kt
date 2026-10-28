package io.github.lumpilabs.beamline.receiver

import io.github.lumpilabs.beamline.protocol.FrameRateDetector.StandardRate
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Running playback figures for one sender, for the statistics overlay and the log. A sender that
 * reconnects keeps its figures, so the totals cover the whole time it mirrors.
 *
 * The decoder and audio threads update their own counters; arrivals are counted on the session
 * thread, which also takes a [snapshot] about once a second.
 */
class PlaybackStats {
  private val startedNs = System.nanoTime()
  private val reconnects = AtomicInteger()

  val framesShown = AtomicLong()

  /** Shown a whole 60 Hz refresh or more after their time, which a viewer sees as a stutter. */
  val framesLate = AtomicLong()

  /** Thrown away undecoded: an overflowing buffer, or frames before the next keyframe. */
  val framesDropped = AtomicLong()

  /** Decoded sound waiting to be heard, or null without sound. */
  @Volatile var soundQueuedMs: Long? = null
  val soundDroppedChunks = AtomicInteger()
  val soundSkippedMs = AtomicLong()
  val soundRestarts = AtomicInteger()

  // Guarded by this.
  private var framesReceived = 0L
  private var bytesReceived = 0L
  private var minTransitNs = Long.MAX_VALUE
  private var maxTransitNs = Long.MIN_VALUE
  private var worstJitterNs = 0L
  private var mark = Mark(startedNs, 0, 0, 0)

  @Synchronized
  fun onVideoArrival(ptsUs: Long, bytes: Int, arrivalNs: Long) {
    framesReceived++
    bytesReceived += bytes
    val transitNs = arrivalNs - ptsUs * 1000
    if (transitNs < minTransitNs) minTransitNs = transitNs
    if (transitNs > maxTransitNs) maxTransitNs = transitNs
  }

  /** The same sender connected again; its timestamps may have started over. */
  @Synchronized
  fun onReconnect() {
    reconnects.incrementAndGet()
    minTransitNs = Long.MAX_VALUE
    maxTransitNs = Long.MIN_VALUE
  }

  /**
   * The figures now, with rates over the time since the last snapshot. Jitter is how unevenly the
   * frames of that time arrived: the spread between the fastest and the slowest one.
   */
  @Synchronized
  fun snapshot(
    video: VideoSize?,
    codec: String?,
    contentRate: StandardRate?,
    delayNs: Long?,
    roundTripUs: Long?,
    skewNs: Long?,
  ): StatsSnapshot {
    val now = System.nanoTime()
    val shown = framesShown.get()
    val seconds = ((now - mark.atNs) / 1e9).coerceAtLeast(0.001)
    val jitterNs = (maxTransitNs - minTransitNs).takeIf { maxTransitNs >= minTransitNs }
    jitterNs?.let { if (it > worstJitterNs) worstJitterNs = it }
    val snapshot = StatsSnapshot(
      elapsedMs = (now - startedNs) / 1_000_000,
      reconnects = reconnects.get(),
      video = video,
      codec = codec,
      receivedFps = (framesReceived - mark.received) / seconds,
      shownFps = (shown - mark.shown) / seconds,
      bitrateKbps = ((bytesReceived - mark.bytes) * 8 / seconds / 1000).toLong(),
      contentRate = contentRate,
      framesLate = framesLate.get(),
      framesDropped = framesDropped.get(),
      delayMs = delayNs?.let { it / 1_000_000 },
      roundTripMs = roundTripUs?.let { it / 1000 },
      jitterMs = jitterNs?.let { it / 1_000_000 },
      worstJitterMs = worstJitterNs / 1_000_000,
      soundQueuedMs = soundQueuedMs,
      skewMs = skewNs?.let { it / 1_000_000 },
      soundDroppedChunks = soundDroppedChunks.get(),
      soundSkippedMs = soundSkippedMs.get(),
      soundRestarts = soundRestarts.get(),
    )
    mark = Mark(now, framesReceived, shown, bytesReceived)
    minTransitNs = Long.MAX_VALUE
    maxTransitNs = Long.MIN_VALUE
    return snapshot
  }

  private class Mark(val atNs: Long, val received: Long, val shown: Long, val bytes: Long)
}

/**
 * Playback figures at one moment. Rates and [jitterMs] cover about the last second; counts cover
 * the whole time the sender has been mirroring. Null means not known (yet).
 */
data class StatsSnapshot(
  val elapsedMs: Long,
  val reconnects: Int,
  val video: VideoSize?,
  /** A [io.github.lumpilabs.beamline.protocol.Codecs] name. */
  val codec: String?,
  val receivedFps: Double,
  val shownFps: Double,
  val bitrateKbps: Long,
  val contentRate: StandardRate?,
  val framesLate: Long,
  val framesDropped: Long,
  /** From capture on the phone to the TV handing the frame to its screen. */
  val delayMs: Long?,
  val roundTripMs: Long?,
  val jitterMs: Long?,
  val worstJitterMs: Long,
  val soundQueuedMs: Long?,
  /** Positive: the sound is heard after the matching picture is shown. */
  val skewMs: Long?,
  val soundDroppedChunks: Int,
  val soundSkippedMs: Long,
  val soundRestarts: Int,
) {
  /** One line for the log, so a long test can be checked afterwards. */
  fun summary(): String =
    "After %d s, %d reconnects: %s, %.1f fps shown of %.1f received, %d kbit/s, content %s, %d late, %d dropped, delay %s ms, round trip %s ms, jitter %s ms (worst %d), sound %s ms queued, skew %s ms, %d chunks dropped, %d ms skipped, %d restarts".format(
      elapsedMs / 1000,
      reconnects,
      video?.let { "${it.width}x${it.height} $codec" } ?: "no video",
      shownFps,
      receivedFps,
      bitrateKbps,
      contentRate?.name ?: "varying",
      framesLate,
      framesDropped,
      delayMs ?: "?",
      roundTripMs ?: "?",
      jitterMs ?: "?",
      worstJitterMs,
      soundQueuedMs ?: "no",
      skewMs ?: "?",
      soundDroppedChunks,
      soundSkippedMs,
      soundRestarts,
    )
}
