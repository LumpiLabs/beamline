package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.view.Surface
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

  @Volatile var surface: Surface? = null

  private val advertiser = ServiceAdvertiser(context)
  private var server: MirrorServer? = null

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

  override fun onConnected(senderName: String) {
    _state.value = ReceiverState.Connected(senderName)
  }

  override fun onVideoSize(size: VideoSize) {
    _state.update { if (it is ReceiverState.Connected) it.copy(video = size) else it }
  }

  override fun onDisconnected(reason: String?) {
    _state.value = ReceiverState.Idle(localAddress(), reason)
  }

  private fun localAddress(): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val links = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
    return links.linkAddresses
      .map { it.address }
      .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
      ?.hostAddress
  }
}
