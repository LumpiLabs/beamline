package io.github.lumpilabs.beamline.windows

import io.github.lumpilabs.beamline.protocol.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * Finds Beamline TVs on the local networks: the `_beamline._tcp` DNS-SD services they announce, as
 * the phone app does. Looks on every private IPv4 network this computer is on, so a VPN such as
 * Tailscale doesn't hide the home network. [onChange] gets the whole list on every change.
 */
class Discovery(private val onChange: (List<TvAddress>) -> Unit) : AutoCloseable {
  private val found = ConcurrentHashMap<String, TvAddress>()
  private val instances = CopyOnWriteArrayList<JmDNS>()

  @Volatile private var closed = false

  private val listener = object : ServiceListener {
    override fun serviceAdded(event: ServiceEvent) {
      event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
    }

    override fun serviceRemoved(event: ServiceEvent) {
      if (found.remove(event.name) != null) changed()
    }

    override fun serviceResolved(event: ServiceEvent) {
      val info = event.info
      val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
      val name = info.getPropertyString(Protocol.Txt.NAME)?.takeIf { it.isNotBlank() } ?: event.name
      found[event.name] = TvAddress(host, info.port, name)
      changed()
    }
  }

  fun start() {
    thread(name = "beamline-discovery", isDaemon = true) {
      for (address in localAddresses()) {
        if (closed) break
        try {
          val dns = JmDNS.create(address)
          instances += dns
          dns.addServiceListener(TYPE, listener)
        } catch (e: Exception) {
          Log.warn("Can't look for TVs on $address", e)
        }
      }
    }
  }

  /** Closing takes JmDNS a moment, so it happens in the background. */
  override fun close() {
    closed = true
    val open = instances.toList()
    instances.clear()
    thread(name = "beamline-discovery-close", isDaemon = true) { open.forEach { runCatching { it.close() } } }
  }

  private fun changed() {
    if (!closed) onChange(found.values.distinctBy { it.host to it.port }.sortedBy { it.name.lowercase() })
  }

  private companion object {
    const val TYPE = "${Protocol.SERVICE_TYPE}.local."
    const val RESOLVE_TIMEOUT_MS = 3_000L

    /** The computer's addresses on home and office networks: private IPv4 on interfaces that are up. */
    fun localAddresses(): List<InetAddress> =
      NetworkInterface.networkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is Inet4Address && it.isSiteLocalAddress }
  }
}
