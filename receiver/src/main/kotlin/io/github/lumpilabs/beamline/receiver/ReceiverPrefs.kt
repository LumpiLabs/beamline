package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Choices made with the remote, kept across restarts of the TV app. Excluded from backups. */
object ReceiverPrefs {
  private const val FILE = "beamline"
  private const val FILL = "fill"
  private const val SHOW_STATS = "show_stats"
  private const val KNOWN_SENDERS = "known_senders"

  fun fill(context: Context): Boolean = prefs(context).getBoolean(FILL, true)

  fun setFill(context: Context, fill: Boolean) = prefs(context).edit { putBoolean(FILL, fill) }

  fun showStats(context: Context): Boolean = prefs(context).getBoolean(SHOW_STATS, false)

  fun setShowStats(context: Context, show: Boolean) = prefs(context).edit { putBoolean(SHOW_STATS, show) }

  /**
   * Sender ids that connected while the TV app was open. Only these may open the app by
   * themselves. A stand-in until pairing: the id isn't secret, but it keeps strangers' phones on
   * the network from popping up on the TV.
   */
  fun isKnownSender(context: Context, senderId: String): Boolean =
    senderId in prefs(context).getStringSet(KNOWN_SENDERS, emptySet()).orEmpty()

  @Synchronized
  fun rememberSender(context: Context, senderId: String) {
    if (isKnownSender(context, senderId)) return
    val known = prefs(context).getStringSet(KNOWN_SENDERS, emptySet()).orEmpty()
    prefs(context).edit { putStringSet(KNOWN_SENDERS, known + senderId) }
  }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
