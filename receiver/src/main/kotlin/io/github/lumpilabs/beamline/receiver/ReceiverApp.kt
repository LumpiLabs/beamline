package io.github.lumpilabs.beamline.receiver

import android.app.Application
import android.os.Build
import android.provider.Settings
import io.github.lumpilabs.beamline.protocol.Identity
import java.io.File

/** Holds the one [ReceiverController] of the process, shared by the TV screen and [ReceiverService]. */
class ReceiverApp : Application() {
  val controller: ReceiverController by lazy {
    val tvName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    // Kept out of backups: a copied key would let another device pass for this TV.
    val identity = Identity.loadOrCreate(File(noBackupFilesDir, "identity"), "Beamline TV")
    ReceiverController(this, tvName, identity)
  }
}
