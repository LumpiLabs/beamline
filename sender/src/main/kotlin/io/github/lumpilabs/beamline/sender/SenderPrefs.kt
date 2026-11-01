package io.github.lumpilabs.beamline.sender

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.lumpilabs.beamline.protocol.Identity
import io.github.lumpilabs.beamline.protocol.Protocol
import java.io.File
import java.util.UUID

/** Small persistent settings of the phone app. Excluded from backups, see data_extraction_rules. */
object SenderPrefs {
  private const val FILE = "beamline"
  private const val LAST_NAME = "last_receiver_name"
  private const val LAST_HOST = "last_receiver_host"
  private const val LAST_PORT = "last_receiver_port"
  private const val TILE_ADDED = "tile_added"
  private const val SENDER_ID = "sender_id"
  private const val KEEP_AWAKE = "keep_awake"
  private const val HEVC = "hevc"
  private const val PAIRED_RECEIVERS = "paired_receivers"

  @Volatile private var identity: Identity? = null

  /** This install's TLS identity, made on first use. Kept out of backups, like everything here. */
  fun identity(context: Context): Identity =
    identity ?: synchronized(this) {
      identity ?: Identity.loadOrCreate(File(context.applicationContext.noBackupFilesDir, "identity"), "Beamline phone")
        .also { identity = it }
    }

  /** Certificate fingerprints of the TVs this phone paired with. */
  fun isPairedReceiver(context: Context, fingerprint: String): Boolean =
    fingerprint in prefs(context).getStringSet(PAIRED_RECEIVERS, emptySet()).orEmpty()

  @Synchronized
  fun rememberPairedReceiver(context: Context, fingerprint: String) {
    val paired = prefs(context).getStringSet(PAIRED_RECEIVERS, emptySet()).orEmpty()
    if (fingerprint !in paired) prefs(context).edit { putStringSet(PAIRED_RECEIVERS, paired + fingerprint) }
  }

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

  /**
   * Keep the screen on, dimmed, while mirroring. On by default: once the phone locks, Android stops
   * sending the picture, which ends a long video on the TV.
   */
  fun keepAwake(context: Context): Boolean = prefs(context).getBoolean(KEEP_AWAKE, true)

  fun setKeepAwake(context: Context, keepAwake: Boolean) = prefs(context).edit { putBoolean(KEEP_AWAKE, keepAwake) }

  /** Send HEVC when the TV takes it. Off by default until it has been tried on more phones. */
  fun hevc(context: Context): Boolean = prefs(context).getBoolean(HEVC, false)

  fun setHevc(context: Context, hevc: Boolean) = prefs(context).edit { putBoolean(HEVC, hevc) }

  /** Random id the TV uses to recognize this phone when it reconnects after a lost connection. */
  @Synchronized
  fun senderId(context: Context): String {
    val prefs = prefs(context)
    prefs.getString(SENDER_ID, null)?.let { return it }
    val id = UUID.randomUUID().toString()
    prefs.edit { putString(SENDER_ID, id) }
    return id
  }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
