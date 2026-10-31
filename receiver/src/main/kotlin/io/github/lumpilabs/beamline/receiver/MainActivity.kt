package io.github.lumpilabs.beamline.receiver

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.FrameRateDetector
import io.github.lumpilabs.beamline.protocol.Protocol
import io.github.lumpilabs.beamline.protocol.VideoSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
  private val controller get() = (application as ReceiverApp).controller

  private val requestLocalNetwork =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) controller.start() else controller.showError(getString(R.string.error_local_network_denied))
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val tvName = controller.tvName
    // From now on the TV stays ready for phones in the background, also after a restart.
    ReceiverService.start(this)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        val state by controller.state.collectAsState()
        val fill by controller.fill.collectAsState()
        val refreshRate by controller.refreshRate.collectAsState()
        val showStats by controller.showStats.collectAsState()
        // Followed only while showing, so the screen isn't recomposed every second for nothing.
        val stats = if (showStats) controller.stats.collectAsState().value else null
        LaunchedEffect(refreshRate) { applyRefreshRate(refreshRate) }
        ReceiverScreen(state, tvName, fill, stats, onSurface = { controller.surface = it })
      }
    }
  }

  /**
   * OK on the remote switches between filling the screen and showing the whole picture; Menu shows
   * or hides the statistics (Up does too, for remotes without a Menu button).
   */
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    // While a new phone waits to pair, OK allows it and Back declines it (instead of leaving the app).
    val pairing = controller.state.value as? ReceiverState.Pairing
    if (pairing?.mustAllow == true && (keyCode in OK_KEYS || keyCode == KeyEvent.KEYCODE_BACK)) {
      controller.answerPairing(keyCode != KeyEvent.KEYCODE_BACK)
      return true
    }
    val streaming = (controller.state.value as? ReceiverState.Connected)?.video != null
    if (streaming && keyCode in OK_KEYS) {
      controller.toggleFill()
      return true
    }
    if (streaming && keyCode in STATS_KEYS) {
      controller.toggleStats()
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  /**
   * Asks the TV for a refresh rate that shows the content evenly, for example 50 Hz for 25 fps
   * video, at the current resolution. Null returns to the TV's default.
   */
  private fun applyRefreshRate(hz: Float?) {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null
    @Suppress("DEPRECATION") // Activity.getDisplay needs Android 11; Fire OS 7 is Android 9.
    val screen = display ?: windowManager.defaultDisplay ?: return
    val current = screen.mode
    val modeId = hz?.let { target ->
      screen.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .minByOrNull { abs(it.refreshRate - target) }
        ?.takeIf { abs(it.refreshRate - target) < 0.5f }
        ?.modeId
    } ?: 0
    if (window.attributes.preferredDisplayModeId == modeId) return
    window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
    Log.i(TAG, "Display mode $modeId for ${hz ?: "default"} Hz")
  }

  override fun onStart() {
    super.onStart()
    controller.onScreenShown()
    if (hasLocalNetworkAccess()) {
      controller.start()
    } else {
      requestLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
    }
  }

  override fun onStop() {
    controller.onScreenHidden()
    super.onStop()
  }

  private fun hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < ANDROID_17 ||
      ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

  private companion object {
    const val TAG = "BeamlineTv"

    /** Android 17 (API 37) gates local network access behind a runtime permission. */
    const val ANDROID_17 = 37

    val OK_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_NUMPAD_ENTER,
      KeyEvent.KEYCODE_BUTTON_A,
    )

    val STATS_KEYS = setOf(
      KeyEvent.KEYCODE_MENU,
      KeyEvent.KEYCODE_INFO,
      KeyEvent.KEYCODE_DPAD_UP,
    )

    /** Manifest.permission.ACCESS_LOCAL_NETWORK, spelled out because the constant is API 37. */
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
  }
}

/** [stats] is null while the statistics overlay is off. */
@Composable
fun ReceiverScreen(
  state: ReceiverState,
  tvName: String,
  fill: Boolean,
  stats: StatsSnapshot?,
  onSurface: (Surface?) -> Unit,
) {
  val video = (state as? ReceiverState.Connected)?.video
  val streaming = video != null
  val view = LocalView.current
  SideEffect { view.keepScreenOn = state is ReceiverState.Connected || state is ReceiverState.Pairing }

  BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    val aspect = if (video != null) video.width.toFloat() / video.height else 16f / 9f
    val screenAspect = maxWidth / maxHeight
    // Filling only makes sense for landscape pictures; a portrait phone would lose most of its screen.
    val cover = fill && aspect >= MIN_FILL_ASPECT
    val wider = aspect > screenAspect
    val (width, height) = when {
      cover == wider -> maxHeight * aspect to maxHeight
      else -> maxWidth to maxWidth / aspect
    }
    VideoSurface(Modifier.requiredSize(width, height), onSurface)
    if (state is ReceiverState.Pairing) {
      PairingScreen(state)
    } else if (!streaming) {
      StatusScreen(state, tvName)
    } else if (aspect >= MIN_FILL_ASPECT) {
      ZoomHint(fill, Modifier.align(Alignment.BottomCenter))
    }
    if (streaming && stats != null) {
      StatsOverlay(stats, (state as ReceiverState.Connected).senderName, Modifier.align(Alignment.TopStart))
    }
  }
}

/** Playback figures over the picture, for checking a long session or a bad Wi-Fi spot. */
@Composable
private fun StatsOverlay(stats: StatsSnapshot, senderName: String, modifier: Modifier) {
  val resources = LocalResources.current
  // Read on every update, so a refresh-rate switch shows within a second.
  val refreshHz = LocalView.current.display?.refreshRate?.roundToInt()
  val unknown = stringResource(R.string.stats_unknown)
  fun ms(value: Long?): String = value?.let { resources.getString(R.string.stats_ms, it) } ?: unknown

  Column(
    modifier
      .padding(32.dp)
      .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(senderName, style = MaterialTheme.typography.titleMedium, color = Color.White)
    StatsRow(
      R.string.stats_mirroring,
      stringResource(R.string.stats_mirroring_value, DateUtils.formatElapsedTime(stats.elapsedMs / 1000), stats.reconnects),
    )
    stats.video?.let {
      val codec = when (stats.codec) {
        Codecs.H264 -> "H.264"
        else -> stats.codec?.uppercase().orEmpty()
      }
      StatsRow(
        R.string.stats_picture,
        stringResource(R.string.stats_picture_value, it.width, it.height, codec, stats.shownFps, stats.bitrateKbps / 1000.0),
      )
    }
    val content = stats.contentRate?.let { stringResource(R.string.stats_content_fps, it.name.removePrefix("FPS_")) }
      ?: stringResource(R.string.stats_content_varying)
    StatsRow(R.string.stats_content, refreshHz?.let { stringResource(R.string.stats_content_value, content, it) } ?: content)
    StatsRow(R.string.stats_delay, stats.delayMs?.let { stringResource(R.string.stats_delay_value, ms(it)) } ?: unknown)
    StatsRow(
      R.string.stats_wifi,
      stringResource(R.string.stats_wifi_value, ms(stats.roundTripMs), ms(stats.jitterMs), ms(stats.worstJitterMs)),
    )
    StatsRow(R.string.stats_frames, stringResource(R.string.stats_frames_value, stats.framesLate, stats.framesDropped))
    val sync = stats.skewMs?.let { stringResource(R.string.stats_signed_ms, it) } ?: unknown
    StatsRow(
      R.string.stats_sound,
      stats.soundQueuedMs?.let { stringResource(R.string.stats_sound_value, ms(it), sync) }
        ?: stringResource(R.string.stats_sound_off),
    )
    StatsRow(
      R.string.stats_sound_gaps,
      stringResource(
        R.string.stats_sound_gaps_value,
        stats.soundDroppedChunks,
        ms(stats.soundSkippedMs),
        stats.soundRestarts,
      ),
    )
  }
}

@Composable
private fun StatsRow(@StringRes label: Int, value: String) {
  // Digits of equal width, so the values don't wobble as they change every second.
  val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
  Row {
    Text(stringResource(label), style = style, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(140.dp))
    Text(value, style = style, color = Color.White)
  }
}

/** Shows for a few seconds when the picture starts and after each OK press. */
@Composable
private fun ZoomHint(fill: Boolean, modifier: Modifier) {
  var visible by remember { mutableStateOf(true) }
  LaunchedEffect(fill) {
    visible = true
    delay(HINT_MS)
    visible = false
  }
  if (!visible) return
  Text(
    stringResource(if (fill) R.string.hint_fill else R.string.hint_fit),
    style = MaterialTheme.typography.bodyLarge,
    color = Color.White,
    modifier = modifier
      .padding(bottom = 48.dp)
      .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
      .padding(horizontal = 20.dp, vertical = 10.dp),
  )
}

private const val MIN_FILL_ASPECT = 1.3f
private const val HINT_MS = 4_000L

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

/** The code to compare with the phone's, large enough to read from the sofa. */
@Composable
private fun PairingScreen(state: ReceiverState.Pairing) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(48.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      stringResource(R.string.pairing_title, state.senderName),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
      state.code.chunked(3).joinToString(" "),
      style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
      color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
      stringResource(R.string.pairing_check),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(if (state.mustAllow) R.string.pairing_allow else R.string.pairing_confirm_on_phone),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
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
      is ReceiverState.Pairing -> stringResource(R.string.pairing_title, state.senderName)
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

@Preview(device = "id:tv_1080p")
@Composable
private fun StatsPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    StatsOverlay(
      StatsSnapshot(
        elapsedMs = 754_000,
        reconnects = 1,
        video = VideoSize(1920, 864),
        codec = Codecs.HEVC,
        receivedFps = 25.0,
        shownFps = 25.0,
        bitrateKbps = 4_200,
        contentRate = FrameRateDetector.StandardRate.FPS_25,
        framesLate = 3,
        framesDropped = 12,
        delayMs = 342,
        roundTripMs = 6,
        jitterMs = 18,
        worstJitterMs = 1_040,
        soundQueuedMs = 312,
        skewMs = 4,
        soundDroppedChunks = 0,
        soundSkippedMs = 205,
        soundRestarts = 1,
      ),
      "Galaxy S26+",
      Modifier,
    )
  }
}
