package io.github.lumpilabs.beamline.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.lumpilabs.beamline.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Finds Beamline TVs with DNS-SD. Services are resolved one at a time, because older Android
 * versions refuse a second resolve while one is running.
 */
class ReceiverDiscovery(context: Context) {
  private val nsd = context.getSystemService(NsdManager::class.java)
  private val _receivers = MutableStateFlow<List<ReceiverAddress>>(emptyList())
  val receivers: StateFlow<List<ReceiverAddress>> = _receivers

  private val toResolve = ArrayDeque<NsdServiceInfo>()
  private var resolving = false
  private var listener: NsdManager.DiscoveryListener? = null

  fun start() {
    if (listener != null) return
    val discovery = object : NsdManager.DiscoveryListener {
      override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)

      override fun onServiceLost(info: NsdServiceInfo) {
        _receivers.update { list -> list.filterNot { it.name == info.serviceName } }
      }

      override fun onDiscoveryStarted(serviceType: String) = Unit

      override fun onDiscoveryStopped(serviceType: String) = Unit

      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(TAG, "Discovery failed: $errorCode")
      }

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }
    nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    listener = discovery
  }

  fun stop() {
    listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
    listener = null
    synchronized(toResolve) {
      toResolve.clear()
      resolving = false
    }
    _receivers.value = emptyList()
  }

  private fun enqueue(info: NsdServiceInfo) {
    synchronized(toResolve) { toResolve.addLast(info) }
    resolveNext()
  }

  @Suppress("DEPRECATION") // resolveService still works and covers Android 10 to 13 too.
  private fun resolveNext() {
    val next = synchronized(toResolve) {
      if (resolving || toResolve.isEmpty()) return
      resolving = true
      toResolve.removeFirst()
    }
    nsd.resolveService(
      next,
      object : NsdManager.ResolveListener {
        override fun onServiceResolved(info: NsdServiceInfo) {
          val host = info.host?.hostAddress
          if (host != null) {
            val name = info.attributes[Protocol.Txt.NAME]?.decodeToString() ?: info.serviceName
            val receiver = ReceiverAddress(name, host, info.port)
            _receivers.update { list -> list.filterNot { it.host == host && it.port == info.port } + receiver }
          }
          done()
        }

        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
          Log.w(TAG, "Resolve of ${info.serviceName} failed: $errorCode")
          done()
        }
      },
    )
  }

  private fun done() {
    synchronized(toResolve) { resolving = false }
    resolveNext()
  }

  private companion object {
    const val TAG = "BeamlineDiscovery"
  }
}
