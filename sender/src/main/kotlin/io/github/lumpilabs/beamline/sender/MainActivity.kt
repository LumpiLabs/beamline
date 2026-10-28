package io.github.lumpilabs.beamline.sender

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.lumpilabs.beamline.protocol.Protocol

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { BeamlineTheme { SenderScreen() } }
  }
}

@Composable
fun BeamlineTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val colors = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    dark -> darkColorScheme()
    else -> lightColorScheme()
  }
  MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun SenderScreen() {
  Scaffold { padding ->
    Column(
      modifier = Modifier.padding(padding).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
      Text(stringResource(R.string.sender_status_placeholder), style = MaterialTheme.typography.bodyLarge)
      Text(
        stringResource(R.string.protocol_version, Protocol.VERSION),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Preview
@Composable
private fun SenderScreenPreview() {
  BeamlineTheme { SenderScreen() }
}
