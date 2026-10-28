package io.github.lumpilabs.beamline.receiver

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.lumpilabs.beamline.protocol.Protocol

class MainActivity : ComponentActivity() {
  private lateinit var controller: ReceiverController

  private val requestLocalNetwork =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) controller.start() else controller.showError(getString(R.string.error_local_network_denied))
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val tvName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    controller = ReceiverController(applicationContext, tvName)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        val state by controller.state.collectAsState()
        ReceiverScreen(state, tvName, onSurface = { controller.surface = it })
      }
    }
  }

  override fun onStart() {
    super.onStart()
    if (hasLocalNetworkAccess()) {
      controller.start()
    } else {
      requestLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
    }
  }

  override fun onStop() {
    controller.stop()
    super.onStop()
  }

  private fun hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < ANDROID_17 ||
      ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

  private companion object {
    /** Android 17 (API 37) gates local network access behind a runtime permission. */
    const val ANDROID_17 = 37

    /** Manifest.permission.ACCESS_LOCAL_NETWORK, spelled out because the constant is API 37. */
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
  }
}

@Composable
fun ReceiverScreen(state: ReceiverState, tvName: String, onSurface: (Surface?) -> Unit) {
  val streaming = state is ReceiverState.Connected && state.video != null
  val view = LocalView.current
  SideEffect { view.keepScreenOn = state is ReceiverState.Connected }

  Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    val video = (state as? ReceiverState.Connected)?.video
    val aspect = if (video != null) video.width.toFloat() / video.height else 16f / 9f
    VideoSurface(Modifier.aspectRatio(aspect), onSurface)
    if (!streaming) StatusScreen(state, tvName)
  }
}

@Composable
private fun VideoSurface(modifier: Modifier, onSurface: (Surface?) -> Unit) {
  AndroidView(
    modifier = modifier,
    factory = { context ->
      SurfaceView(context).apply {
        holder.addCallback(
          object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
          },
        )
      }
    },
  )
}

@Composable
private fun StatusScreen(state: ReceiverState, tvName: String) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(48.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      stringResource(R.string.app_name),
      style = MaterialTheme.typography.displayMedium,
      color = MaterialTheme.colorScheme.onBackground,
    )
    val headline = when (state) {
      is ReceiverState.Idle -> stringResource(R.string.idle_waiting)
      is ReceiverState.Connected -> stringResource(R.string.connected_waiting_for_video, state.senderName)
      is ReceiverState.Failed -> state.message
    }
    Text(headline, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
    Text(
      stringResource(R.string.idle_device_name, tvName),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state is ReceiverState.Idle) {
      state.address?.let {
        Text(
          stringResource(R.string.idle_address, it),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      state.lastError?.let {
        Text(
          stringResource(R.string.idle_last_session_ended, it),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Text(
      stringResource(R.string.protocol_version, Protocol.VERSION),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun IdlePreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    StatusScreen(ReceiverState.Idle("192.168.1.50"), "Fire TV Stick 4K Max")
  }
}
