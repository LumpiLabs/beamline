package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import io.github.lumpilabs.beamline.protocol.ByeReasons
import io.github.lumpilabs.beamline.protocol.FrameRateDetector.StandardRate
import io.github.lumpilabs.beamline.protocol.Identity
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

sealed interface ReceiverState {
  data class Idle(val address: String?, val lastError: String? = null) : ReceiverState

  data class Connected(val senderName: String, val video: VideoSize? = null) : ReceiverState

  /** Pairing with [senderName]: [code] shows on both screens. [mustAllow]: OK on the remote is needed too. */
  data class Pairing(val senderName: String, val code: String, val mustAllow: Boolean) : ReceiverState

  data class Failed(val message: String) : ReceiverState
}

/**
 * Owns the server and the network announcement. Where [ReceiverService] works, they keep running
 * while the TV app is closed, so a phone that connected before can open the app by connecting;
 * elsewhere they run only while the app is on screen. There is one per process, see [ReceiverApp].
 */
class ReceiverController(
  private val context: Context,
  val tvName: String,
  private val identity: Identity,
) : MirrorServer.Events {
  private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle(null))
  val state: StateFlow<ReceiverState> = _state

  /** Fill the screen (crop a wider picture) instead of showing all of it with black bars. */
  private val _fill = MutableStateFlow(ReceiverPrefs.fill(context))
  val fill: StateFlow<Boolean> = _fill

  /** Display refresh rate that shows the content evenly, or null for the TV's default. */
  private val _refreshRate = MutableStateFlow<Float?>(null)
  val refreshRate: StateFlow<Float?> = _refreshRate

  /** The latest playback figures while a sender is connected. */
  private val _stats = MutableStateFlow<StatsSnapshot?>(null)
  val stats: StateFlow<StatsSnapshot?> = _stats

  /** Whether the statistics overlay is on; it stays on, also after a restart, until switched off. */
  private val _showStats = MutableStateFlow(ReceiverPrefs.showStats(context))
  val showStats: StateFlow<Boolean> = _showStats

  @Volatile var surface: Surface? = null

  /** Whether the TV app is on screen. Set on the main thread, read on session threads. */
  @Volatile var isScreenShown = false
    private set

  /**
   * Keeps Wi-Fi out of power saving while a phone mirrors: in power saving, the access point holds
   * frames back and delivers them in bunches. The TV runs on mains power, so this costs nothing
   * that matters.
   */
  private val wifiLock = context.getSystemService(WifiManager::class.java)
    ?.createWifiLock(WIFI_LOCK_MODE, "Beamline:mirroring")
    ?.apply { setReferenceCounted(false) }

  private val advertiser = ServiceAdvertiser(context)
  private var server: MirrorServer? = null
  private var candidateHz: Float? = null
  private var candidateReports = 0

  /** The TV user's answer to the pairing on screen, if one is waiting. */
  @Volatile private var pendingPairing: CompletableFuture<Boolean>? = null

  fun start() {
    if (server != null) return
    val newServer = MirrorServer(tvName, identity, { surface }, this)
    try {
      val port = newServer.start()
      advertiser.register(tvName, port)
      server = newServer
      _state.value = ReceiverState.Idle(localAddress())
    } catch (e: IOException) {
      newServer.stop()
      _state.value = ReceiverState.Failed(e.message ?: e.javaClass.simpleName)
    }
  }

  fun stop() {
    advertiser.unregister()
    server?.stop()
    server = null
    wifiLock?.release()
  }

  fun onScreenShown() {
    isScreenShown = true
  }

  /**
   * Leaving the TV app ends mirroring and tells the phone why: otherwise the phone would reconnect
   * and pull the user back into the app. Without [ReceiverService], the server stops too.
   */
  fun onScreenHidden() {
    isScreenShown = false
    // A pairing nobody can answer any more is declined rather than left to time out.
    answerPairing(false)
    if (ReceiverService.isSupported) server?.endSession(ByeReasons.APP_CLOSED) else stop()
  }

  fun showError(message: String) {
    _state.value = ReceiverState.Failed(message)
  }

  fun toggleFill() {
    ReceiverPrefs.setFill(context, _fill.updateAndGet { !it })
  }

  fun toggleStats() {
    ReceiverPrefs.setShowStats(context, _showStats.updateAndGet { !it })
  }

  /** OK (true) or Back (false) on the remote while a pairing waits for the TV's user. */
  fun answerPairing(allowed: Boolean) {
    pendingPairing?.complete(allowed)
  }

  override fun isPaired(fingerprint: String): Boolean = ReceiverPrefs.isPairedSender(context, fingerprint)

  override fun rememberPaired(fingerprint: String) = ReceiverPrefs.rememberPairedSender(context, fingerprint)

  /**
   * Pairing needs the TV app on screen, for the code and the OK. Paired phones may connect while
   * it's closed: connecting opens it for them.
   */
  override fun refusal(pairing: Boolean): String? = ByeReasons.OPEN_APP.takeIf { pairing && !isScreenShown }

  override fun askPairing(senderName: String, code: String, mustAllow: Boolean): CompletableFuture<Boolean> {
    val answer = CompletableFuture<Boolean>()
    if (mustAllow) pendingPairing = answer else answer.complete(true)
    _state.value = ReceiverState.Pairing(senderName, code, mustAllow)
    return answer
  }

  override fun onPairingFinished() {
    pendingPairing?.complete(false)
    pendingPairing = null
    _state.update { if (it is ReceiverState.Pairing) ReceiverState.Idle(localAddress()) else it }
  }

  /** A sender reconnecting after a lost connection keeps its picture on screen until new frames come. */
  override fun onConnected(senderName: String) {
    wifiLock?.acquire()
    _state.update {
      if (it is ReceiverState.Connected && it.senderName == senderName) it else ReceiverState.Connected(senderName)
    }
    if (!isScreenShown) bringToFront()
  }

  /** Wakes the TV, also from its screensaver, and opens the TV app to show the picture. */
  private fun bringToFront() {
    Log.i(TAG, "A known phone connected, opening the TV app")
    // The newer way to wake the screen is an activity flag, but the activity isn't running yet.
    @Suppress("DEPRECATION")
    context.getSystemService(PowerManager::class.java)
      ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Beamline:wake")
      ?.acquire(WAKE_MS)
    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  override fun onVideoSize(size: VideoSize) {
    _state.update { if (it is ReceiverState.Connected) it.copy(video = size) else it }
  }

  /**
   * Called about once a second. Switching the refresh rate blanks the TV for a moment, so it only
   * happens after the same rate was seen several times in a row, and switching back to the default
   * takes even longer: player controls briefly showing over a 25 fps video shouldn't trigger it.
   */
  override fun onContentFrameRate(rate: StandardRate?) {
    val hz = rate?.displayHz ?: return
    if (hz == candidateHz) {
      candidateReports++
    } else {
      candidateHz = hz
      candidateReports = 1
    }
    // 60 Hz is the TV's default; only 24, 25 and 50 fps content needs a different rate.
    val wanted = hz.takeIf { it != DEFAULT_HZ }
    val needed = if (wanted == null) SWITCH_BACK_AFTER_REPORTS else SWITCH_AFTER_REPORTS
    if (candidateReports >= needed && _refreshRate.value != wanted) _refreshRate.value = wanted
  }

  override fun onStats(stats: StatsSnapshot) {
    _stats.value = stats
  }

  override fun onDisconnected(reason: MirrorServer.EndReason?) {
    wifiLock?.release()
    candidateHz = null
    candidateReports = 0
    _refreshRate.value = null
    _stats.value = null
    _state.value = ReceiverState.Idle(localAddress(), reason?.let { context.getString(it.message) })
  }

  private fun localAddress(): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val links = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
    return links.linkAddresses
      .map { it.address }
      .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
      ?.hostAddress
  }

  private companion object {
    const val TAG = "BeamlineTv"

    /** Long enough for the TV app to come up and keep the screen on itself. */
    const val WAKE_MS = 5_000L
    const val SWITCH_AFTER_REPORTS = 4
    const val SWITCH_BACK_AFTER_REPORTS = 15
    const val DEFAULT_HZ = 60f

    /**
     * Low-latency mode exists from Android 10 and works while the app is in front with the screen
     * on, as it is here. Before that (Fire OS 7 is Android 9), high-performance mode does the job.
     */
    @Suppress("DEPRECATION")
    val WIFI_LOCK_MODE =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
      } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
      }
  }
}
