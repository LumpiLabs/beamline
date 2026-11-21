package io.github.lumpilabs.beamline.receiver

/**
 * A phone paired with this TV: its certificate [fingerprint], the [name] it last connected with,
 * and when it paired and last connected, in milliseconds since 1970.
 */
data class PairedSender(val fingerprint: String, val name: String, val pairedAtMs: Long, val lastUsedMs: Long) {
  /** The stored form, keyed by the fingerprint: the times, then the name, which may contain anything. */
  fun encode(): String = "$pairedAtMs;$lastUsedMs;$name"

  companion object {
    /** Null for a value that can't be read. */
    fun decode(fingerprint: String, value: String): PairedSender? {
      val parts = value.split(";", limit = 3)
      if (parts.size != 3) return null
      val pairedAtMs = parts[0].toLongOrNull() ?: return null
      val lastUsedMs = parts[1].toLongOrNull() ?: return null
      return PairedSender(fingerprint, parts[2], pairedAtMs, lastUsedMs)
    }
  }
}
