package io.github.lumpilabs.beamline.sender

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.lumpilabs.beamline.protocol.Protocol

/** Small persistent settings of the phone app. Excluded from backups, see data_extraction_rules. */
object SenderPrefs {
  private const val FILE = "beamline"
  private const val LAST_NAME = "last_receiver_name"
  private const val LAST_HOST = "last_receiver_host"
  private const val LAST_PORT = "last_receiver_port"
  private const val TILE_ADDED = "tile_added"

  /** The TV mirrored to most recently; the Quick Settings tile mirrors to it. */
  fun lastReceiver(context: Context): ReceiverAddress? {
    val prefs = prefs(context)
    val host = prefs.getString(LAST_HOST, null) ?: return null
    return ReceiverAddress(
      prefs.getString(LAST_NAME, null) ?: host,
      host,
      prefs.getInt(LAST_PORT, Protocol.DEFAULT_PORT),
    )
  }

  fun setLastReceiver(context: Context, receiver: ReceiverAddress) {
    prefs(context).edit {
      putString(LAST_NAME, receiver.name)
      putString(LAST_HOST, receiver.host)
      putInt(LAST_PORT, receiver.port)
    }
  }

  fun isTileAdded(context: Context): Boolean = prefs(context).getBoolean(TILE_ADDED, false)

  fun setTileAdded(context: Context) = prefs(context).edit { putBoolean(TILE_ADDED, true) }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
