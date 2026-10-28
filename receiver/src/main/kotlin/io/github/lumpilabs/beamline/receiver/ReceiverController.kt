package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.view.Surface
import io.github.lumpilabs.beamline.protocol.FrameRateDetector.StandardRate
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.io.IOException
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

sealed interface ReceiverState {
  data class Idle(val address: String?, val lastError: String? = null) : ReceiverState

  data class Connected(val senderName: String, val video: VideoSize? = null) : ReceiverState

  data class Failed(val message: String) : ReceiverState
}

/** Owns the server and the network announcement while the TV app is in the foreground. */
class ReceiverController(private val context: Context, private val tvName: String) : MirrorServer.Events {
  private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle(null))
  val state: StateFlow<ReceiverState> = _state

  /** Fill the screen (crop a wider picture) instead of showing all of it with black bars. */
  private val _fill = MutableStateFlow(true)
  val fill: StateFlow<Boolean> = _fill

  /** Display refresh rate that shows the content evenly, or null for the TV's default. */
  private val _refreshRate = MutableStateFlow<Float?>(null)
  val refreshRate: StateFlow<Float?> = _refreshRate

  /** The latest playback figures while a sender is connected. */
  private val _stats = MutableStateFlow<StatsSnapshot?>(null)
  val stats: StateFlow<StatsSnapshot?> = _stats

  /** Whether the statistics overlay is on; it stays on for the next sender until switched off. */
  private val _showStats = MutableStateFlow(false)
  val showStats: StateFlow<Boolean> = _showStats

  @Volatile var surface: Surface? = null

  private val advertiser = ServiceAdvertiser(context)
  private var server: MirrorServer? = null
  private var candidateHz: Float? = null
  private var candidateReports = 0

  fun start() {
    if (server != null) return
    val newServer = MirrorServer(tvName, { surface }, this)
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
  }

  fun showError(message: String) {
    _state.value = ReceiverState.Failed(message)
  }

  fun toggleFill() {
    _fill.update { !it }
  }

  fun toggleStats() {
    _showStats.update { !it }
  }

  /** A sender reconnecting after a lost connection keeps its picture on screen until new frames come. */
  override fun onConnected(senderName: String) {
    _state.update {
      if (it is ReceiverState.Connected && it.senderName == senderName) it else ReceiverState.Connected(senderName)
    }
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
    const val SWITCH_AFTER_REPORTS = 4
    const val SWITCH_BACK_AFTER_REPORTS = 15
    const val DEFAULT_HZ = 60f
  }
}
