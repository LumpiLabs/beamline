package io.github.lumpilabs.beamline.sender

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible screen that asks Android for screen-capture permission and then starts mirroring.
 * Used by the app and by the Quick Settings tile, which can't show the system prompt itself.
 */
class CaptureActivity : ComponentActivity() {
  private val requestCapture =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val target = targetFrom(intent)
      val data = result.data
      if (result.resultCode == RESULT_OK && data != null && target != null) {
        SenderPrefs.setLastReceiver(this, target)
        MirrorService.start(this, result.resultCode, data, target)
      }
      finish()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (targetFrom(intent) == null) {
      finish()
      return
    }
    if (savedInstanceState == null) {
      requestCapture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
  }

  companion object {
    private const val EXTRA_NAME = "name"
    private const val EXTRA_HOST = "host"
    private const val EXTRA_PORT = "port"

    fun intent(context: Context, target: ReceiverAddress): Intent =
      Intent(context, CaptureActivity::class.java)
        .putExtra(EXTRA_NAME, target.name)
        .putExtra(EXTRA_HOST, target.host)
        .putExtra(EXTRA_PORT, target.port)

    private fun targetFrom(intent: Intent): ReceiverAddress? {
      val host = intent.getStringExtra(EXTRA_HOST) ?: return null
      val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it in 1..65535 } ?: return null
      return ReceiverAddress(intent.getStringExtra(EXTRA_NAME) ?: host, host, port)
    }
  }
}
