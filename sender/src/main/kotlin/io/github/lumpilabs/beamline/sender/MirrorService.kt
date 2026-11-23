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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that holds the screen capture for one session. Android requires the
 * service to be in the foreground before the capture token can be used.
 *
 * While mirroring it can also keep the screen on, dimmed ([SenderPrefs.keepAwake]): once the phone
 * locks, Android stops sending the picture.
 *
 * Its notification says where the session stands, so it never claims to mirror while the phone is
 * still connecting or pairing. When a first connection asks whether the TV shows the same pairing
 * code, the notification asks it, with the code and both answers, and pops up: mirroring often
 * starts from the Quick Settings tile with the app closed.
 */
class MirrorService : Service() {
  @Volatile private var session: SenderSession? = null
  private var screenLock: PowerManager.WakeLock? = null
  private val scope = MainScope()

  /** Set once the notification is the foreground one; touched on the main thread only. */
  private var inForeground = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    scope.launch { MirrorState.status.collect(::showStatus) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      session?.stop(null) ?: stopSelf()
      return START_NOT_STICKY
    }
    if (intent?.action == ACTION_PAIRING_ANSWER) {
      session?.answerPairing(intent.getBooleanExtra(EXTRA_MATCHES, false)) ?: stopSelf()
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

    val connecting = checkNotNull(notification(MirrorStatus.Connecting(target.name)))
    startForeground(NOTIFICATION_ID, connecting, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    inForeground = true
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
    scope.cancel()
    super.onDestroy()
  }

  /** Brings the notification up to date with the session; the service ends with Idle and Failed. */
  private fun showStatus(status: MirrorStatus) {
    if (!inForeground) return
    val notification = notification(status) ?: return
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
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

  /**
   * The notification for [status], or null for Idle and Failed. The pairing question goes on a
   * channel that pops up, with both answers instead of Stop; everything else stays quiet.
   */
  private fun notification(status: MirrorStatus): Notification? {
    val (title, text) = when (status) {
      is MirrorStatus.Connecting -> getString(R.string.status_connecting, status.receiver) to null
      is MirrorStatus.Pairing ->
        getString(R.string.pairing_notification_title, status.receiver) to
          getString(R.string.status_pairing, status.receiver, spacedCode(status.code))
      is MirrorStatus.AllowOnTv ->
        getString(R.string.pairing_notification_title, status.receiver) to getString(R.string.status_allow_on_tv, status.receiver)
      is MirrorStatus.Mirroring -> getString(R.string.notification_title, status.receiver) to null
      is MirrorStatus.Reconnecting -> getString(R.string.status_reconnecting, status.receiver) to null
      MirrorStatus.Idle, is MirrorStatus.Failed -> return null
    }
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
    manager.createNotificationChannel(
      NotificationChannel(PAIRING_CHANNEL_ID, getString(R.string.pairing_channel), NotificationManager.IMPORTANCE_HIGH),
    )
    val asks = status is MirrorStatus.Pairing
    val builder = Notification.Builder(this, if (asks) PAIRING_CHANNEL_ID else CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
      .setOngoing(true)
    text?.let { builder.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
    if (asks) {
      builder.addAction(action(R.string.pairing_matches, pairingAnswer(true)))
      builder.addAction(action(R.string.pairing_differs, pairingAnswer(false)))
    } else {
      val stop = Intent(this, MirrorService::class.java).setAction(ACTION_STOP)
      builder.addAction(action(R.string.stop, PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE)))
    }
    return builder.build()
  }

  private fun action(label: Int, intent: PendingIntent): Notification.Action =
    Notification.Action.Builder(null, getString(label), intent).build()

  private fun pairingAnswer(matches: Boolean): PendingIntent =
    PendingIntent.getService(
      this,
      if (matches) 1 else 2,
      Intent(this, MirrorService::class.java).setAction(ACTION_PAIRING_ANSWER).putExtra(EXTRA_MATCHES, matches),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  companion object {
    private const val ACTION_STOP = "io.github.lumpilabs.beamline.sender.STOP"
    private const val ACTION_KEEP_AWAKE = "io.github.lumpilabs.beamline.sender.KEEP_AWAKE"
    private const val ACTION_PAIRING_ANSWER = "io.github.lumpilabs.beamline.sender.PAIRING_ANSWER"
    private const val EXTRA_MATCHES = "matches"
    private const val PAIRING_CHANNEL_ID = "pairing"
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

    /** The user's answer to the pairing question in the app: true when the TV shows the same code. */
    fun answerPairing(context: Context, matches: Boolean) {
      context.startService(
        Intent(context, MirrorService::class.java).setAction(ACTION_PAIRING_ANSWER).putExtra(EXTRA_MATCHES, matches),
      )
    }
  }
}
