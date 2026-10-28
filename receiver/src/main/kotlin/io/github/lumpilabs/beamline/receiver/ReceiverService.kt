package io.github.lumpilabs.beamline.receiver

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the TV ready for phones while the TV app is closed: the server and the network
 * announcement stay up, and a phone that connected before opens the app by connecting. Started
 * when the app opens and after the TV boots ([BootReceiver]).
 *
 * Only where an app may open itself from the background: up to Android 9, which covers Fire OS 6
 * and 7. Newer Android blocks that unless the user grants drawing over other apps; there the
 * server runs only while the app is open, as before.
 */
class ReceiverService : Service() {
  private val controller get() = (application as ReceiverApp).controller

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, notification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    controller.start()
    return START_STICKY
  }

  override fun onDestroy() {
    if (!controller.isScreenShown) controller.stop()
    super.onDestroy()
  }

  private fun notification(): Notification {
    NotificationManagerCompat.from(this).createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
        .setName(getString(R.string.ready_channel))
        .build(),
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.ready_title))
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "ready"
    private const val NOTIFICATION_ID = 1

    val isSupported: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun start(context: Context) {
      if (isSupported) ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
    }
  }
}
