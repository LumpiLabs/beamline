package io.github.lumpilabs.beamline.airplay

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * UxPlay's AirPlay server, for Apple devices mirroring their screen. It announces nothing itself:
 * [start] returns the DNS-SD services to announce (with NsdManager), and [Listener] gets the client's
 * requests and its decrypted video and sound. One server per process.
 */
class AirPlayServer(private val listener: Listener) {
  /**
   * Called on UxPlay's threads; video and sound each come from a thread of their own. The arrays are
   * the listener's to keep.
   */
  interface Listener {
    /**
     * An Apple device asks to mirror; false turns it away. While one mirrors, another one isn't
     * asked: it is told the TV is busy.
     */
    fun onClientRequest(deviceId: String, model: String, name: String): Boolean

    /** A connection opened or closed; [open] is how many are open now. */
    fun onConnections(open: Int)

    /** Mirroring started or stopped sending video. */
    fun onMirroring(running: Boolean)

    /** The connection to the client broke (UxPlay's reason code); the server listens again by itself. */
    fun onConnectionLost(reason: Int)

    /** The client's screen size and codec, before its first video. */
    fun onVideoFormat(h265: Boolean, width: Int, height: Int)

    /**
     * Decrypted video: [nalCount] NAL units in Annex B (start codes), parameter sets in front of
     * keyframes. [senderTimeNs]: when the sender captured it, on the sender's clock (NTP time),
     * the same clock as the sound's.
     */
    fun onVideo(nals: ByteArray, h265: Boolean, nalCount: Int, senderTimeNs: Long)

    /** The sound's format: [compressionType] 8 is AAC-ELD (mirroring), 2 ALAC, 4 AAC-LC; [samplesPerFrame]. */
    fun onAudioFormat(compressionType: Int, samplesPerFrame: Int, usingScreen: Boolean)

    /** One decrypted sound frame; [senderTimeNs] on the same clock as the video's. */
    fun onAudio(frame: ByteArray, compressionType: Int, senderTimeNs: Long)
  }

  /** A DNS-SD service to announce, with its TXT record. */
  data class Service(val type: String, val name: String, val port: Int, val txt: Map<String, String>)

  /**
   * [deviceId]: the 6-byte "MAC address" AirPlay identifies the TV by. [keyFile]: where UxPlay keeps
   * its Ed25519 key, so Apple devices recognise the TV after a restart. [width], [height] and [fps]:
   * the screen the clients are asked to send.
   */
  class Config(
    val name: String,
    val deviceId: ByteArray,
    val keyFile: String,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val h265: Boolean = true,
    val debugLog: Boolean = false,
  )

  private val resets = Executors.newSingleThreadExecutor { Thread(it, "AirPlayReset") }
  @Volatile private var handle = 0L
  /**
   * Counted without this object's lock: [restart] and [stop] hold it while UxPlay joins its
   * threads, and those threads report closing connections on their way out.
   */
  private val openConnections = AtomicInteger()

  /** Fires when a restart after a lost connection moved the server to another port. */
  var onServicesChanged: ((List<Service>) -> Unit)? = null

  /** Starts the server and returns the services to announce, or throws if it can't start. */
  @Synchronized
  fun start(config: Config): List<Service> {
    check(handle == 0L) { "already started" }
    require(config.deviceId.size == 6) { "device ID must be 6 bytes" }
    val started = nativeStart(
      config.name, config.deviceId, config.keyFile,
      config.width, config.height, config.fps, config.h265, config.debugLog,
    )
    if (started == 0L) throw IllegalStateException("AirPlay server didn't start; see the UxPlay log")
    handle = started
    return services()
  }

  @Synchronized
  fun stop() {
    val stopping = handle
    if (stopping == 0L) return
    handle = 0L
    nativeStop(stopping)
  }

  private fun services(): List<Service> = listOf(SERVICE_AIRPLAY to "_airplay._tcp", SERVICE_RAOP to "_raop._tcp")
    .mapNotNull { (service, type) ->
      val name = nativeServiceName(handle, service) ?: return@mapNotNull null
      Service(type, name, nativePort(handle, service), TxtRecord.parse(nativeServiceTxt(handle, service)))
    }

  /** Drops the client, if any, so another sender can have the screen. Doesn't block. */
  fun dropClient() {
    resets.execute(::restart)
  }

  /** Drops the client and listens again, as UxPlay does after a lost connection. Not on UxPlay's threads. */
  private fun restart() {
    synchronized(this) {
      if (handle == 0L) return
      val before = nativePort(handle, SERVICE_AIRPLAY)
      if (!nativeRestart(handle)) {
        Log.e(TAG, "Couldn't listen again after a lost connection")
        return
      }
      if (nativePort(handle, SERVICE_AIRPLAY) == before) return
      services()
    }.let { services -> onServicesChanged?.invoke(services) }
  }

  // Called from native code.

  @Suppress("unused")
  private fun onClientRequest(deviceId: String?, model: String?, name: String?): Boolean {
    Log.i(TAG, "Connection request from $name ($model)")
    return listener.onClientRequest(deviceId.orEmpty(), model.orEmpty(), name.orEmpty())
  }

  @Suppress("unused")
  private fun onConnectionOpened() {
    listener.onConnections(openConnections.incrementAndGet())
  }

  @Suppress("unused")
  private fun onConnectionClosed() {
    listener.onConnections(openConnections.decrementAndGet())
  }

  @Suppress("unused")
  private fun onConnectionReset(reason: Int) {
    Log.i(TAG, "Connection reset (reason $reason)")
    listener.onConnectionLost(reason)
    resets.execute(::restart)
  }

  @Suppress("unused")
  private fun onMirrorRunning(running: Boolean) = listener.onMirroring(running)

  @Suppress("unused")
  private fun onVideoFormat(h265: Boolean, width: Int, height: Int) = listener.onVideoFormat(h265, width, height)

  @Suppress("unused")
  private fun onVideo(nals: ByteArray, h265: Boolean, nalCount: Int, ntpLocal: Long, ntpRemote: Long) =
    listener.onVideo(nals, h265, nalCount, ntpRemote)

  @Suppress("unused")
  private fun onVideoReset(type: Int) {
    Log.i(TAG, "Video reset (type $type)")
  }

  @Suppress("unused")
  private fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean, format: Long) =
    listener.onAudioFormat(ct, spf, usingScreen)

  @Suppress("unused")
  private fun onAudio(frame: ByteArray, ct: Int, ntpLocal: Long, ntpRemote: Long) = listener.onAudio(frame, ct, ntpRemote)

  private external fun nativeStart(
    name: String, deviceId: ByteArray, keyFile: String,
    width: Int, height: Int, fps: Int, h265: Boolean, debug: Boolean,
  ): Long

  private external fun nativeServiceName(handle: Long, service: Int): String?

  private external fun nativeServiceTxt(handle: Long, service: Int): ByteArray

  private external fun nativePort(handle: Long, service: Int): Int

  private external fun nativeRestart(handle: Long): Boolean

  private external fun nativeStop(handle: Long)

  companion object {
    private const val TAG = "BeamlineAirPlay"

    // DNSSD_SERVICE_RAOP and DNSSD_SERVICE_AIRPLAY in UxPlay's dnssd.h.
    private const val SERVICE_RAOP = 0
    private const val SERVICE_AIRPLAY = 1

    init {
      System.loadLibrary("beamline-airplay")
    }
  }
}
