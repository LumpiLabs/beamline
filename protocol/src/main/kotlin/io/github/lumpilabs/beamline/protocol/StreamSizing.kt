package io.github.lumpilabs.beamline.protocol

import kotlin.math.max
import kotlin.math.min

data class VideoSize(val width: Int, val height: Int)

/** Picks the encoded video size from the phone's screen size and the receiver's limit. */
object StreamSizing {
  /** Many hardware codecs want dimensions in multiples of 16. */
  const val ALIGNMENT = 16

  /**
   * Scales [source] down (never up) to fit inside [limit], keeping the aspect ratio, and rounds
   * both sides down to [ALIGNMENT]. A portrait phone on a 1920x1080 TV is limited by the height.
   */
  fun fit(source: VideoSize, limit: VideoSize): VideoSize {
    require(source.width > 0 && source.height > 0) { "Source size must be positive: $source" }
    require(limit.width >= ALIGNMENT && limit.height >= ALIGNMENT) { "Limit is too small: $limit" }
    val scale = min(1.0, min(limit.width.toDouble() / source.width, limit.height.toDouble() / source.height))
    return VideoSize(align(source.width * scale), align(source.height * scale))
  }

  private fun align(value: Double): Int = max(ALIGNMENT, value.toInt() / ALIGNMENT * ALIGNMENT)
}
