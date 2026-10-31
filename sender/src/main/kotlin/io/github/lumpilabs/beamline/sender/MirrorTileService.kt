package io.github.lumpilabs.beamline.sender

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap mirrors to the last TV, another tap stops. Without a known TV it
 * opens the app to pick one.
 */
class MirrorTileService : TileService() {
  private var scope: CoroutineScope? = null

  override fun onStartListening() {
    scope = MainScope().also { it.launch { MirrorState.status.collect(::render) } }
  }

  override fun onStopListening() {
    scope?.cancel()
    scope = null
  }

  override fun onClick() {
    if (MirrorState.status.value.isActive) {
      MirrorService.stop(this)
      return
    }
    val target = SenderPrefs.lastReceiver(this)
    val intent = if (target != null) CaptureActivity.intent(this, target) else Intent(this, MainActivity::class.java)
    launchAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  // Android 10 to 13 only have the Intent variant; 14 and newer use the PendingIntent one.
  @SuppressLint("StartActivityAndCollapseDeprecated")
  @Suppress("DEPRECATION")
  private fun launchAndCollapse(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(
        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
      )
    } else {
      startActivityAndCollapse(intent)
    }
  }

  private fun render(status: MirrorStatus) {
    val tile = qsTile ?: return
    tile.state = if (status.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.subtitle = when (status) {
      is MirrorStatus.Mirroring -> status.receiver
      is MirrorStatus.Connecting -> getString(R.string.tile_connecting)
      is MirrorStatus.Reconnecting -> getString(R.string.tile_reconnecting)
      is MirrorStatus.Pairing -> getString(R.string.tile_check_code)
      is MirrorStatus.AllowOnTv -> getString(R.string.tile_allow_on_tv)
      else -> SenderPrefs.lastReceiver(this)?.name ?: getString(R.string.tile_pick_tv)
    }
    tile.updateTile()
  }

  companion object {
    fun component(context: Context) = ComponentName(context, MirrorTileService::class.java)

    /** Asks the system to refresh the tile soon, even while the panel is closed. */
    fun requestUpdate(context: Context) {
      runCatching { requestListeningState(context, component(context)) }
    }
  }
}
