package io.github.lumpilabs.beamline.protocol

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Peak level of 16-bit little-endian PCM, for diagnostics: is there any sound at all? */
object AudioLevel {
  /** Peak in dBFS, from 0 (full scale) down; -96 for digital silence. */
  fun peakDbfs(pcm: ByteArray, length: Int = pcm.size): Double {
    var peak = 0
    var i = 0
    while (i + 1 < length) {
      val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
      peak = max(peak, abs(sample.toShort().toInt()))
      i += 2
    }
    return if (peak == 0) -96.0 else 20 * log10(peak / 32768.0)
  }
}
