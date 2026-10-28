package io.github.lumpilabs.beamline.sender

import android.Manifest
import android.app.StatusBarManager
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.lumpilabs.beamline.protocol.Protocol
import io.github.lumpilabs.beamline.protocol.VideoSize

class MainActivity : ComponentActivity() {
  private lateinit var discovery: ReceiverDiscovery
  private var tileAdded by mutableStateOf(false)
  private var soundAllowed by mutableStateOf(true)

  private val requestPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      soundAllowed = hasPermission(Manifest.permission.RECORD_AUDIO)
      startDiscoveryIfAllowed()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    discovery = ReceiverDiscovery(applicationContext)
    tileAdded = SenderPrefs.isTileAdded(this)
    setContent {
      BeamlineTheme {
        val status by MirrorState.status.collectAsState()
        val receivers by discovery.receivers.collectAsState()
        SenderScreen(
          status = status,
          receivers = receivers,
          localNetworkAllowed = hasLocalNetworkAccess(),
          soundAllowed = soundAllowed,
          onConnect = { startActivity(CaptureActivity.intent(this, it)) },
          onStop = { MirrorService.stop(this) },
          onAddTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !tileAdded) ::requestAddTile else null,
        )
      }
    }
  }

  /** Asks Android to put the Beamline tile into Quick Settings (Android 13 and newer). */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun requestAddTile() {
    getSystemService(StatusBarManager::class.java).requestAddTileService(
      MirrorTileService.component(this),
      getString(R.string.app_name),
      Icon.createWithResource(this, R.drawable.ic_notification),
      mainExecutor,
    ) { result ->
      if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
      ) {
        SenderPrefs.setTileAdded(this)
        tileAdded = true
      }
    }
  }

  override fun onStart() {
    super.onStart()
    val missing = buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
      if (Build.VERSION.SDK_INT >= ANDROID_17) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.filterNot(::hasPermission)
    soundAllowed = hasPermission(Manifest.permission.RECORD_AUDIO)
    if (missing.isEmpty()) startDiscoveryIfAllowed() else requestPermissions.launch(missing.toTypedArray())
  }

  override fun onStop() {
    discovery.stop()
    super.onStop()
  }

  private fun startDiscoveryIfAllowed() {
    if (hasLocalNetworkAccess()) discovery.start()
  }

  private fun hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < ANDROID_17 || hasPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

  private companion object {
    /** Android 17 (API 37) gates local network access behind a runtime permission. */
    const val ANDROID_17 = 37
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
fun SenderScreen(
  status: MirrorStatus,
  receivers: List<ReceiverAddress>,
  localNetworkAllowed: Boolean,
  soundAllowed: Boolean,
  onConnect: (ReceiverAddress) -> Unit,
  onStop: () -> Unit,
  onAddTile: (() -> Unit)?,
) {
  Scaffold { padding ->
    LazyColumn(
      modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Text(
          stringResource(R.string.app_name),
          style = MaterialTheme.typography.headlineLarge,
          modifier = Modifier.padding(top = 24.dp),
        )
      }
      item { StatusCard(status, onStop) }
      if (onAddTile != null) {
        item { AddTileCard(onAddTile) }
      }
      if (!localNetworkAllowed) {
        item { Text(stringResource(R.string.local_network_needed), style = MaterialTheme.typography.bodyMedium) }
      }
      if (!soundAllowed) {
        item { Text(stringResource(R.string.sound_needed), style = MaterialTheme.typography.bodyMedium) }
      }
      item { Text(stringResource(R.string.tvs_on_network), style = MaterialTheme.typography.titleMedium) }
      if (receivers.isEmpty()) {
        item { Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium) }
      }
      items(receivers, key = { "${it.host}:${it.port}" }) { receiver ->
        ListItem(
          headlineContent = { Text(receiver.name) },
          supportingContent = { Text(receiver.host) },
          modifier = Modifier.clickable { onConnect(receiver) },
        )
      }
      item { ManualAddress(onConnect) }
      item {
        Text(
          stringResource(R.string.protocol_version, Protocol.VERSION),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(bottom = 24.dp),
        )
      }
    }
  }
}

@Composable
private fun StatusCard(status: MirrorStatus, onStop: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(16.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val text = when (status) {
        MirrorStatus.Idle -> stringResource(R.string.status_idle)
        is MirrorStatus.Connecting -> stringResource(R.string.status_connecting, status.receiver)
        is MirrorStatus.Mirroring ->
          stringResource(R.string.status_mirroring, status.receiver, status.size.width, status.size.height)
        is MirrorStatus.Failed -> stringResource(R.string.status_failed, status.message)
      }
      Text(text, modifier = Modifier.weight(1f))
      if (status is MirrorStatus.Connecting || status is MirrorStatus.Mirroring) {
        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
      }
    }
  }
}

@Composable
private fun AddTileCard(onAddTile: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.add_tile_explanation), style = MaterialTheme.typography.bodyMedium)
      FilledTonalButton(onClick = onAddTile) { Text(stringResource(R.string.add_tile)) }
    }
  }
}

@Composable
private fun ManualAddress(onConnect: (ReceiverAddress) -> Unit) {
  var address by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
      value = address,
      onValueChange = { address = it.trim() },
      label = { Text(stringResource(R.string.manual_hint)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    Button(
      onClick = { parseAddress(address)?.let(onConnect) },
      enabled = parseAddress(address) != null,
    ) { Text(stringResource(R.string.connect)) }
  }
}

/** Accepts `host` or `host:port`. */
internal fun parseAddress(text: String): ReceiverAddress? {
  if (text.isBlank()) return null
  val host = text.substringBeforeLast(':', text)
  val port = if (host == text) Protocol.DEFAULT_PORT else text.substringAfterLast(':').toIntOrNull() ?: return null
  if (host.isBlank() || port !in 1..65535) return null
  return ReceiverAddress(host, host, port)
}

@Preview
@Composable
private fun SenderScreenPreview() {
  BeamlineTheme {
    SenderScreen(
      status = MirrorStatus.Mirroring("Fire TV Stick 4K Max", VideoSize(480, 1072)),
      receivers = listOf(ReceiverAddress("Fire TV Stick 4K Max", "192.168.1.50", Protocol.DEFAULT_PORT)),
      localNetworkAllowed = true,
      soundAllowed = true,
      onConnect = {},
      onStop = {},
      onAddTile = {},
    )
  }
}
