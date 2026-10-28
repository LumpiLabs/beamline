package io.github.lumpilabs.beamline.sender

import android.annotation.SuppressLint
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
import android.os.PowerManager
import androidx.core.content.IntentCompat

/**
 * Foreground service that holds the screen capture for one session. Android requires the
 * service to be in the foreground before the capture token can be used.
 *
 * While mirroring it can also keep the screen on, dimmed ([SenderPrefs.keepAwake]): once the phone
 * locks, Android stops sending the picture.
 */
class MirrorService : Service() {
  @Volatile private var session: SenderSession? = null
  private var screenLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      session?.stop(null) ?: stopSelf()
      return START_NOT_STICKY
    }
    if (intent?.action == ACTION_KEEP_AWAKE) {
      if (session != null) updateScreenLock() else stopSelf()
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
      MirrorState.set(this, MirrorStatus.Failed(getString(R.string.error_capture_denied)))
      stopSelf()
      return START_NOT_STICKY
    }
    // Mirroring to another TV replaces the session. The old one ending must not stop the service,
    // or the new session would go down with it.
    val previous = session
    lateinit var started: SenderSession
    started = SenderSession(this, projection, target) { if (session === started) stopSelf() }
    session = started
    previous?.stop(null)
    started.start()
    updateScreenLock()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    session?.stop(null)
    session = null
    screenLock?.release()
    screenLock = null
    super.onDestroy()
  }

  /**
   * Holds or lets go of the screen to match the setting, which can change while mirroring. The lock
   * is held as long as mirroring, which can go on for hours, and released in [onDestroy].
   */
  @SuppressLint("Wakelock", "WakelockTimeout")
  private fun updateScreenLock() {
    val held = screenLock?.isHeld == true
    if (SenderPrefs.keepAwake(this) && !held) {
      // FLAG_KEEP_SCREEN_ON would need a window of ours on screen, but the user is in another app.
      @Suppress("DEPRECATION")
      screenLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Beamline:mirroring")
        .apply {
          setReferenceCounted(false)
          acquire()
        }
    } else if (!SenderPrefs.keepAwake(this) && held) {
      screenLock?.release()
      screenLock = null
    }
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
    private const val ACTION_KEEP_AWAKE = "io.github.lumpilabs.beamline.sender.KEEP_AWAKE"
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

    /** Applies a changed [SenderPrefs.keepAwake] to the running session. */
    fun updateKeepAwake(context: Context) {
      context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_KEEP_AWAKE))
    }
  }
}
