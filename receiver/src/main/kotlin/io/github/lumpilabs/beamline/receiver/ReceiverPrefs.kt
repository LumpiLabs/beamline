package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Choices made with the remote, kept across restarts of the TV app. Excluded from backups. */
object ReceiverPrefs {
  private const val FILE = "beamline"
  private const val FILL = "fill"
  private const val SHOW_STATS = "show_stats"
  private const val PAIRED_SENDERS = "paired_senders"

  fun fill(context: Context): Boolean = prefs(context).getBoolean(FILL, true)

  fun setFill(context: Context, fill: Boolean) = prefs(context).edit { putBoolean(FILL, fill) }

  fun showStats(context: Context): Boolean = prefs(context).getBoolean(SHOW_STATS, false)

  fun setShowStats(context: Context, show: Boolean) = prefs(context).edit { putBoolean(SHOW_STATS, show) }

  /** Certificate fingerprints of the phones paired with this TV. */
  fun isPairedSender(context: Context, fingerprint: String): Boolean =
    fingerprint in prefs(context).getStringSet(PAIRED_SENDERS, emptySet()).orEmpty()

  @Synchronized
  fun rememberPairedSender(context: Context, fingerprint: String) {
    val paired = prefs(context).getStringSet(PAIRED_SENDERS, emptySet()).orEmpty()
    if (fingerprint !in paired) prefs(context).edit { putStringSet(PAIRED_SENDERS, paired + fingerprint) }
  }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
