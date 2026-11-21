package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Choices made with the remote, kept across restarts of the TV app. Excluded from backups. */
object ReceiverPrefs {
  private const val FILE = "beamline"
  private const val FILL = "fill"
  private const val SHOW_STATS = "show_stats"

  /** One entry per paired phone, keyed by its certificate fingerprint, see [PairedSender.encode]. */
  private const val PAIRED_FILE = "paired_senders"

  fun fill(context: Context): Boolean = prefs(context).getBoolean(FILL, true)

  fun setFill(context: Context, fill: Boolean) = prefs(context).edit { putBoolean(FILL, fill) }

  fun showStats(context: Context): Boolean = prefs(context).getBoolean(SHOW_STATS, false)

  fun setShowStats(context: Context, show: Boolean) = prefs(context).edit { putBoolean(SHOW_STATS, show) }

  /** The phones paired with this TV, the one used last first. */
  fun pairedSenders(context: Context): List<PairedSender> =
    paired(context).all
      .mapNotNull { (fingerprint, value) -> (value as? String)?.let { PairedSender.decode(fingerprint, it) } }
      .sortedByDescending { it.lastUsedMs }

  fun isPairedSender(context: Context, fingerprint: String): Boolean = paired(context).contains(fingerprint)

  @Synchronized
  fun rememberPairedSender(context: Context, fingerprint: String, name: String, nowMs: Long) {
    paired(context).edit { putString(fingerprint, PairedSender(fingerprint, name, nowMs, nowMs).encode()) }
  }

  /** A paired phone connected: keeps its newest name and when it was last used. */
  @Synchronized
  fun touchPairedSender(context: Context, fingerprint: String, name: String, nowMs: Long) {
    val sender = paired(context).getString(fingerprint, null)?.let { PairedSender.decode(fingerprint, it) } ?: return
    paired(context).edit { putString(fingerprint, sender.copy(name = name, lastUsedMs = nowMs).encode()) }
  }

  @Synchronized
  fun forgetPairedSender(context: Context, fingerprint: String) = paired(context).edit { remove(fingerprint) }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  private fun paired(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PAIRED_FILE, Context.MODE_PRIVATE)
}
