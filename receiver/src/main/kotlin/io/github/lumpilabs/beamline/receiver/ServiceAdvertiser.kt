package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Protocol

/**
 * Announces the receiver on the local network over DNS-SD: as `_beamline._tcp` for the phones, and
 * as `_airplay._tcp` and `_raop._tcp` for Apple devices. Each service type is announced once; a new
 * announcement of a type replaces the old one.
 */
class ServiceAdvertiser(context: Context) {
  private val nsd = context.getSystemService(NsdManager::class.java)
  private val listeners = mutableMapOf<String, NsdManager.RegistrationListener>()

  fun register(name: String, port: Int) {
    register(
      Protocol.SERVICE_TYPE, name, port,
      mapOf(
        Protocol.Txt.VERSION to Protocol.VERSION.toString(),
        Protocol.Txt.NAME to name,
        Protocol.Txt.CODECS to "${Codecs.H264},${Codecs.OPUS}",
        Protocol.Txt.RESOLUTION to with(MirrorServer.MAX_SIZE) { "${width}x$height@${MirrorServer.MAX_FPS}" },
      ),
    )
  }

  @Synchronized
  fun register(type: String, name: String, port: Int, txt: Map<String, String>) {
    unregister(type)
    val info = NsdServiceInfo().apply {
      serviceName = name
      serviceType = type
      this.port = port
      txt.forEach { (key, value) -> setAttribute(key, value) }
    }
    val registration = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) {
        Log.i(TAG, "Registered $type as ${info.serviceName}")
      }

      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "Registration of $type failed: $errorCode")
      }

      override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

      override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }
    nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    listeners[type] = registration
  }

  @Synchronized
  fun unregister(type: String) {
    listeners.remove(type)?.let { runCatching { nsd.unregisterService(it) } }
  }

  @Synchronized
  fun unregister() {
    listeners.keys.toList().forEach(::unregister)
  }

  private companion object {
    const val TAG = "BeamlineNsd"
  }
}
