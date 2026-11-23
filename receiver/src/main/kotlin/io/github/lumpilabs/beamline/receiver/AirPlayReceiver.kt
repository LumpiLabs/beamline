package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import io.github.lumpilabs.beamline.airplay.AirPlayServer
import java.io.File
import java.security.SecureRandom
import java.util.Locale

/**
 * Lets iPads, iPhones and Macs mirror to the TV over AirPlay, next to [MirrorServer] for the phones.
 * For now (M4 step 1) it only logs what arrives: the client, the video format, and how much video
 * and sound comes in. The player comes in the next step.
 */
class AirPlayReceiver(private val context: Context, private val advertiser: ServiceAdvertiser) : AirPlayServer.Listener {
  private var server: AirPlayServer? = null
  private val videoLog = FlowLog("video")
  private val audioLog = FlowLog("sound")
  @Volatile private var loggedVideoBuffers = 0

  /**
   * Starts the server and announces it. Throws [IllegalStateException] if the server can't start,
   * or [UnsatisfiedLinkError] on a device without its native code (an x86 emulator).
   */
  fun start(tvName: String) {
    val dir = File(context.noBackupFilesDir, "airplay").apply { mkdirs() }
    val config = AirPlayServer.Config(
      name = tvName,
      deviceId = deviceId(File(dir, "device-id")),
      keyFile = File(dir, "key.pem").path,
      debugLog = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
    val newServer = server ?: AirPlayServer(this).also { server = it }
    newServer.onServicesChanged = ::announce
    announce(newServer.start(config))
  }

  fun stop() {
    AIRPLAY_TYPES.forEach(advertiser::unregister)
    server?.stop()
  }

  private fun announce(services: List<AirPlayServer.Service>) {
    services.forEach { advertiser.register(it.type, it.name, it.port, it.txt) }
  }

  override fun onClientRequest(deviceId: String, model: String, name: String): Boolean {
    Log.i(TAG, "$name ($model) asks to connect")
    return true
  }

  override fun onConnections(open: Int) {
    Log.i(TAG, "Open AirPlay connections: $open")
  }

  override fun onMirroring(running: Boolean) {
    Log.i(TAG, if (running) "Mirroring started" else "Mirroring stopped")
    if (running) loggedVideoBuffers = 0
  }

  override fun onVideoFormat(h265: Boolean, width: Int, height: Int) {
    Log.i(TAG, "Video: ${if (h265) "H.265" else "H.264"}, ${width}x$height")
  }

  override fun onVideo(nals: ByteArray, h265: Boolean, nalCount: Int, ntpLocalNs: Long) {
    if (loggedVideoBuffers < FIRST_BUFFERS_LOGGED) {
      loggedVideoBuffers++
      Log.i(TAG, "Video buffer: ${nals.size} bytes, NAL types ${NalUnits.types(nals, h265)}")
    }
    videoLog.add(nals.size)
  }

  override fun onAudioFormat(compressionType: Int, samplesPerFrame: Int, usingScreen: Boolean) {
    val format = when (compressionType) {
      2 -> "ALAC"
      4 -> "AAC-LC"
      8 -> "AAC-ELD"
      else -> "type $compressionType"
    }
    Log.i(TAG, "Sound: $format, $samplesPerFrame samples per frame${if (usingScreen) ", mirroring" else ""}")
  }

  override fun onAudio(frame: ByteArray, compressionType: Int, ntpLocalNs: Long) {
    audioLog.add(frame.size)
  }

  /** Logs every few seconds how many buffers and bytes came in. */
  private class FlowLog(private val what: String) {
    private var since = 0L
    private var count = 0
    private var bytes = 0L

    @Synchronized
    fun add(size: Int) {
      val now = SystemClock.elapsedRealtime()
      if (since == 0L) since = now
      count++
      bytes += size
      val elapsed = now - since
      if (elapsed >= INTERVAL_MS) {
        val perSecond = 1000.0 / elapsed
        Log.i(TAG, "AirPlay $what: %.1f/s, %.0f kbit/s".format(Locale.ROOT, count * perSecond, bytes * 8 * perSecond / 1000))
        since = now
        count = 0
        bytes = 0
      }
    }
  }

  private companion object {
    const val TAG = "BeamlineAirPlay"
    const val INTERVAL_MS = 5_000L
    const val FIRST_BUFFERS_LOGGED = 3
    val AIRPLAY_TYPES = listOf("_airplay._tcp", "_raop._tcp")

    /**
     * The "MAC address" AirPlay knows the TV by. Android doesn't reveal the real one, so the TV
     * makes one up once: random, marked locally administered and unicast.
     */
    fun deviceId(file: File): ByteArray {
      runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == 6 }?.let { return it }
      val id = ByteArray(6).also(SecureRandom()::nextBytes)
      id[0] = ((id[0].toInt() and 0xFC) or 0x02).toByte()
      file.writeBytes(id)
      return id
    }
  }
}
