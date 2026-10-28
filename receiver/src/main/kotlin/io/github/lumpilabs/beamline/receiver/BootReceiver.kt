package io.github.lumpilabs.beamline.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Gets the TV ready for phones again after it restarts, without opening the app. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReceiverService.start(context)
  }
}
