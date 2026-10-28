package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Choices made with the remote, kept across restarts of the TV app. Excluded from backups. */
object ReceiverPrefs {
  private const val FILE = "beamline"
  private const val FILL = "fill"
  private const val SHOW_STATS = "show_stats"

  fun fill(context: Context): Boolean = prefs(context).getBoolean(FILL, true)

  fun setFill(context: Context, fill: Boolean) = prefs(context).edit { putBoolean(FILL, fill) }

  fun showStats(context: Context): Boolean = prefs(context).getBoolean(SHOW_STATS, false)

  fun setShowStats(context: Context, show: Boolean) = prefs(context).edit { putBoolean(SHOW_STATS, show) }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
