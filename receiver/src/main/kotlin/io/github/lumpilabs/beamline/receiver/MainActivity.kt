package io.github.lumpilabs.beamline.receiver

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.lumpilabs.beamline.protocol.Protocol

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val deviceName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) { IdleScreen(deviceName) }
    }
  }
}

@Composable
fun IdleScreen(deviceName: String) {
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
    Text(
      stringResource(R.string.idle_waiting),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
      stringResource(R.string.idle_device_name, deviceName),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(R.string.protocol_version, Protocol.VERSION),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun IdleScreenPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) { IdleScreen("Fire TV Stick 4K Max") }
}
