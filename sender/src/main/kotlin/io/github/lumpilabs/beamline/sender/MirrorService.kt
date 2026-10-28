package io.github.lumpilabs.beamline.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.content.IntentCompat

/**
 * Foreground service that holds the screen capture for one session. Android requires the
 * service to be in the foreground before the capture token can be used.
 */
class MirrorService : Service() {
  private var session: SenderSession? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      session?.stop(null) ?: stopSelf()
      return START_NOT_STICKY
    }
    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
    val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
    val target = intent?.let {
      ReceiverAddress(
        it.getStringExtra(EXTRA_NAME) ?: "",
        it.getStringExtra(EXTRA_HOST) ?: "",
        it.getIntExtra(EXTRA_PORT, 0),
      )
    }
    if (data == null || target == null || target.host.isEmpty()) {
      stopSelf()
      return START_NOT_STICKY
    }

    startForeground(NOTIFICATION_ID, notification(target.name), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
    if (projection == null) {
      MirrorState.set(MirrorStatus.Failed(getString(R.string.error_capture_denied)))
      stopSelf()
      return START_NOT_STICKY
    }
    session?.stop(null)
    session = SenderSession(this, projection, target) { stopSelf() }.also { it.start() }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    session?.stop(null)
    session = null
    super.onDestroy()
  }

  private fun notification(receiverName: String): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
    val stopIntent = PendingIntent.getService(
      this,
      0,
      Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val openIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.notification_title, receiverName))
      .setContentIntent(openIntent)
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, getString(R.string.stop), stopIntent).build())
      .build()
  }

  companion object {
    private const val ACTION_STOP = "io.github.lumpilabs.beamline.sender.STOP"
    private const val EXTRA_RESULT_CODE = "result_code"
    private const val EXTRA_RESULT_DATA = "result_data"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_HOST = "host"
    private const val EXTRA_PORT = "port"
    private const val CHANNEL_ID = "mirroring"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context, resultCode: Int, data: Intent, target: ReceiverAddress) {
      val intent = Intent(context, MirrorService::class.java)
        .putExtra(EXTRA_RESULT_CODE, resultCode)
        .putExtra(EXTRA_RESULT_DATA, data)
        .putExtra(EXTRA_NAME, target.name)
        .putExtra(EXTRA_HOST, target.host)
        .putExtra(EXTRA_PORT, target.port)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
    }
  }
}
