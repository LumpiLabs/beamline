package io.github.lumpilabs.beamline.receiver

import android.app.Application
import android.os.Build
import android.provider.Settings

/** Holds the one [ReceiverController] of the process, shared by the TV screen and [ReceiverService]. */
class ReceiverApp : Application() {
  val controller: ReceiverController by lazy {
    val tvName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    ReceiverController(this, tvName)
  }
}
