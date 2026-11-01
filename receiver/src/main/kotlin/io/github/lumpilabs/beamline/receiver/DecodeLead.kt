package io.github.lumpilabs.beamline.receiver

/**
 * How long before its time a frame goes into the decoder. Decoders differ a lot: on the Fire TV
 * Stick 4K Max at 2336 x 1072, H.264 takes about 40 ms and HEVC about 85 ms. A fixed 60 ms made
 * every HEVC frame late, so the lead follows the measured decode times: slower frames pull it up
 * quickly, faster ones let it down slowly. A single slow frame only moves it part of the way,
 * because a lead well above the usual decode time makes the decoder hold finished frames, and it
 * has only a few output buffers.
 *
 * Used on the decoder's thread only.
 */
class DecodeLead {
  /** The current lead, in nanoseconds. */
  var ns = MIN_NS
    private set

  /** How long decoding a frame takes, smoothed; null before the first frame. */
  var decodeNs: Long? = null
    private set

  /** A frame came out of the decoder [tookNs] after it went in. */
  fun onDecoded(tookNs: Long) {
    val took = tookNs.coerceAtLeast(0)
    decodeNs = decodeNs?.let { it + (took - it) / AVERAGE_FRAMES } ?: took
    val wanted = (took + MARGIN_NS).coerceIn(MIN_NS, MAX_NS)
    ns += if (wanted > ns) (wanted - ns) / RISE_FRAMES else (wanted - ns) / FALL_FRAMES
  }

  companion object {
    /** Enough for H.264, which has always worked with it. */
    const val MIN_NS = 60_000_000L

    /**
     * The decoded frame waits up to [VideoDecoder]'s hold limit for its time; beyond that it
     * would be shown early. This keeps the lead below it.
     */
    const val MAX_NS = 100_000_000L

    /** One refresh at 50 Hz: decode times vary a little from frame to frame. */
    const val MARGIN_NS = 20_000_000L

    /** A run of slower frames moves the lead most of the way within about this many frames. */
    const val RISE_FRAMES = 4

    /** Coming back down takes a few seconds of faster frames. */
    const val FALL_FRAMES = 64

    const val AVERAGE_FRAMES = 8
  }
}
