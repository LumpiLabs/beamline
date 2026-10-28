package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Protocol

/** Announces the receiver on the local network as a `_beamline._tcp` DNS-SD service. */
class ServiceAdvertiser(context: Context) {
  private val nsd = context.getSystemService(NsdManager::class.java)
  private var listener: NsdManager.RegistrationListener? = null

  fun register(name: String, port: Int) {
    unregister()
    val info = NsdServiceInfo().apply {
      serviceName = name
      serviceType = Protocol.SERVICE_TYPE
      this.port = port
      setAttribute(Protocol.Txt.VERSION, Protocol.VERSION.toString())
      setAttribute(Protocol.Txt.NAME, name)
      setAttribute(Protocol.Txt.CODECS, Codecs.H264)
      setAttribute(Protocol.Txt.RESOLUTION, "1920x1080@60")
    }
    val registration = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) {
        Log.i(TAG, "Registered as ${info.serviceName}")
      }

      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "Registration failed: $errorCode")
      }

      override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

      override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }
    nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    listener = registration
  }

  fun unregister() {
    listener?.let { runCatching { nsd.unregisterService(it) } }
    listener = null
  }

  private companion object {
    const val TAG = "BeamlineNsd"
  }
}
