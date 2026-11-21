package io.github.lumpilabs.beamline.windows

import io.github.lumpilabs.beamline.protocol.Identity
import io.github.lumpilabs.beamline.protocol.Protocol
import java.io.File
import java.util.Properties
import java.util.UUID

/** A TV to mirror to: where it is and what it is called. */
data class TvAddress(val host: String, val port: Int = Protocol.DEFAULT_PORT, val name: String = host)

/**
 * What the Windows sender keeps between runs, in %APPDATA%\Beamline: its TLS identity, its sender
 * id, the TVs it paired with, and the user's choices.
 */
class Settings(private val directory: File) {
  private val file = File(directory, "settings.properties")
  private val properties = Properties()

  init {
    if (file.isFile) runCatching { file.inputStream().use(properties::load) }
  }

  val identity: Identity by lazy { Identity.loadOrCreate(directory, "Beamline for Windows") }

  /** Stays the same for this install, so the TV lets a reconnect replace the lost connection. */
  val senderId: String
    @Synchronized get() = properties.getProperty(SENDER_ID) ?: UUID.randomUUID().toString().also { set(SENDER_ID, it) }

  @Synchronized
  fun isPaired(fingerprint: String): Boolean = fingerprint in pairedTvs()

  @Synchronized
  fun rememberPaired(fingerprint: String) = set(PAIRED_TVS, (pairedTvs() + fingerprint).joinToString(","))

  var lowLatency: Boolean
    @Synchronized get() = properties.getProperty(LOW_LATENCY) == "true"

    @Synchronized set(value) = set(LOW_LATENCY, value.toString())

  /** The screen mirrored last, see [Screen.index]. */
  var screen: Int
    @Synchronized get() = properties.getProperty(SCREEN)?.toIntOrNull() ?: 0

    @Synchronized set(value) = set(SCREEN, value.toString())

  var lastTv: TvAddress?
    @Synchronized get() {
      val host = properties.getProperty(LAST_TV_HOST) ?: return null
      val port = properties.getProperty(LAST_TV_PORT)?.toIntOrNull() ?: Protocol.DEFAULT_PORT
      return TvAddress(host, port, properties.getProperty(LAST_TV_NAME) ?: host)
    }

    @Synchronized set(value) {
      if (value == null) return
      properties.setProperty(LAST_TV_HOST, value.host)
      properties.setProperty(LAST_TV_PORT, value.port.toString())
      set(LAST_TV_NAME, value.name)
    }

  private fun pairedTvs(): Set<String> = properties.getProperty(PAIRED_TVS).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

  private fun set(key: String, value: String) {
    properties.setProperty(key, value)
    directory.mkdirs()
    val temporary = File(directory, "${file.name}.new")
    temporary.outputStream().use { properties.store(it, "Beamline for Windows") }
    if (!temporary.renameTo(file)) {
      file.delete()
      temporary.renameTo(file)
    }
  }

  companion object {
    private const val SENDER_ID = "sender_id"
    private const val PAIRED_TVS = "paired_tvs"
    private const val LOW_LATENCY = "low_latency"
    private const val SCREEN = "screen"
    private const val LAST_TV_HOST = "last_tv_host"
    private const val LAST_TV_PORT = "last_tv_port"
    private const val LAST_TV_NAME = "last_tv_name"

    val directory: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Beamline")

    fun load(): Settings = Settings(directory)
  }
}
