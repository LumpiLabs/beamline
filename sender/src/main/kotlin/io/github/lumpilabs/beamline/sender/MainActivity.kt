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
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Protocol
import io.github.lumpilabs.beamline.protocol.VideoSize

class MainActivity : ComponentActivity() {
  private lateinit var discovery: ReceiverDiscovery
  private var tileAdded by mutableStateOf(false)
  private var soundAllowed by mutableStateOf(true)
  private var keepAwake by mutableStateOf(true)
  private var hevc by mutableStateOf(false)

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
    keepAwake = SenderPrefs.keepAwake(this)
    hevc = SenderPrefs.hevc(this)
    setContent {
      BeamlineTheme {
        val status by MirrorState.status.collectAsState()
        val receivers by discovery.receivers.collectAsState()
        SenderScreen(
          status = status,
          receivers = receivers,
          localNetworkAllowed = hasLocalNetworkAccess(),
          soundAllowed = soundAllowed,
          keepAwake = keepAwake,
          hevc = hevc,
          onConnect = { startActivity(CaptureActivity.intent(this, it)) },
          onStop = { MirrorService.stop(this) },
          onPairingAnswer = { MirrorService.answerPairing(this, it) },
          onAddTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !tileAdded) ::requestAddTile else null,
          onKeepAwakeChange = ::changeKeepAwake,
          onHevcChange = ::changeHevc,
        )
      }
    }
  }

  private fun changeKeepAwake(enabled: Boolean) {
    SenderPrefs.setKeepAwake(this, enabled)
    keepAwake = enabled
    if (MirrorState.status.value.isActive) MirrorService.updateKeepAwake(this)
  }

  /** Applies from the next encoder start: the next session, or a rotation. */
  private fun changeHevc(enabled: Boolean) {
    SenderPrefs.setHevc(this, enabled)
    hevc = enabled
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
  keepAwake: Boolean,
  hevc: Boolean,
  onConnect: (ReceiverAddress) -> Unit,
  onStop: () -> Unit,
  onPairingAnswer: (Boolean) -> Unit,
  onAddTile: (() -> Unit)?,
  onKeepAwakeChange: (Boolean) -> Unit,
  onHevcChange: (Boolean) -> Unit,
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
      item { StatusCard(status, onStop, onPairingAnswer) }
      if (onAddTile != null) {
        item { AddTileCard(onAddTile) }
      }
      item { SwitchSetting(R.string.keep_awake, R.string.keep_awake_explanation, keepAwake, onKeepAwakeChange) }
      item { SwitchSetting(R.string.hevc, R.string.hevc_explanation, hevc, onHevcChange) }
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
private fun StatusCard(status: MirrorStatus, onStop: () -> Unit, onPairingAnswer: (Boolean) -> Unit) {
  if (status is MirrorStatus.Pairing) {
    PairingCard(status, onPairingAnswer)
    return
  }
  Card(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(16.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val text = when (status) {
        MirrorStatus.Idle -> stringResource(R.string.status_idle)
        is MirrorStatus.Connecting -> stringResource(R.string.status_connecting, status.receiver)
        is MirrorStatus.Mirroring -> stringResource(
          R.string.status_mirroring,
          status.receiver,
          status.size.width,
          status.size.height,
          if (status.codec == Codecs.HEVC) "HEVC" else "H.264",
        )
        is MirrorStatus.Reconnecting -> stringResource(R.string.status_reconnecting, status.receiver)
        is MirrorStatus.Pairing -> stringResource(R.string.status_pairing, status.receiver, spacedCode(status.code))
        is MirrorStatus.AllowOnTv -> stringResource(R.string.status_allow_on_tv, status.receiver)
        is MirrorStatus.Failed -> stringResource(R.string.status_failed, status.message)
      }
      Text(text, modifier = Modifier.weight(1f))
      if (status.isActive) {
        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
      }
    }
  }
}

/** First connection to a TV: the code to compare with the one on the TV, and the two answers. */
@Composable
private fun PairingCard(status: MirrorStatus.Pairing, onAnswer: (Boolean) -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.pairing_question, status.receiver), style = MaterialTheme.typography.bodyLarge)
      Text(
        spacedCode(status.code),
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        modifier = Modifier.align(Alignment.CenterHorizontally),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
        OutlinedButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.pairing_differs)) }
        Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.pairing_matches)) }
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
private fun SwitchSetting(
  @StringRes title: Int,
  @StringRes explanation: Int,
  checked: Boolean,
  onChange: (Boolean) -> Unit,
) {
  ListItem(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = { Text(stringResource(explanation)) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
  )
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
      status = MirrorStatus.Mirroring("Fire TV Stick 4K Max", VideoSize(480, 1072), Codecs.H264),
      receivers = listOf(ReceiverAddress("Fire TV Stick 4K Max", "192.168.1.50", Protocol.DEFAULT_PORT)),
      localNetworkAllowed = true,
      soundAllowed = true,
      keepAwake = true,
      hevc = false,
      onConnect = {},
      onStop = {},
      onPairingAnswer = {},
      onAddTile = {},
      onKeepAwakeChange = {},
      onHevcChange = {},
    )
  }
}
