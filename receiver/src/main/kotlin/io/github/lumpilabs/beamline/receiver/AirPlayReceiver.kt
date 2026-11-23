package io.github.lumpilabs.beamline.receiver

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.Surface
import io.github.lumpilabs.beamline.airplay.AirPlayServer
import io.github.lumpilabs.beamline.protocol.AudioConfig
import io.github.lumpilabs.beamline.protocol.ByeReasons
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
import io.github.lumpilabs.beamline.protocol.FrameType
import io.github.lumpilabs.beamline.protocol.StreamConfig
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Lets iPads, iPhones and Macs mirror to the TV over AirPlay, next to [MirrorServer] for phones and
 * laptops. Their picture and sound go through the same player ([VideoDecoder], [AudioPlayer],
 * [PlaybackClock]), and the same busy and take-over rules apply, see [Screen].
 */
class AirPlayReceiver(
  private val context: Context,
  private val advertiser: ServiceAdvertiser,
  private val screen: Screen,
  private val videoSurface: () -> Surface?,
  private val events: MirrorServer.Events,
) : AirPlayServer.Listener {
  @Volatile private var server: AirPlayServer? = null

  /** The Apple device's session, from its request until it ends; null otherwise. */
  @Volatile private var session: Session? = null

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
    session?.finish(null)
    server?.stop()
  }

  private fun announce(services: List<AirPlayServer.Service>) {
    services.forEach { advertiser.register(it.type, it.name, it.port, it.txt) }
  }

  /**
   * Decides with [Admission], like a phone's hello: no pairing yet (the PIN comes later), and an
   * Apple device that comes back is the same sender.
   */
  override fun onClientRequest(deviceId: String, model: String, name: String): Boolean {
    val newSession = Session(name, "airplay:$deviceId")
    val previous = synchronized(screen.lock) {
      val current = screen.occupant
      val admission = Admission.decide(
        onScreen = current != null,
        sameSender = current?.senderId == newSession.senderId,
        pairing = false,
        reconnecting = false,
        takeOverAllowed = events.allowsTakeOver(),
      )
      if (admission.refusal != null) {
        Log.i(TAG, "Turned $name ($model) away: ${admission.refusal}")
        newSession.discard()
        return false
      }
      screen.occupant = newSession
      current
    }
    when (previous) {
      null -> Unit
      // UxPlay takes one Apple device at a time, so this is the same one again.
      is Session -> previous.finish(null)
      else -> {
        Log.i(TAG, "$name ($model) takes over the screen")
        previous.end(ByeReasons.REPLACED)
        screen.awaitGone(previous)
      }
    }
    session = newSession
    Log.i(TAG, "$name ($model) mirrors")
    events.onConnected(name, null)
    return true
  }

  override fun onConnections(open: Int) {
    if (open == 0) session?.finish(null)
  }

  override fun onMirroring(running: Boolean) {
    Log.i(TAG, if (running) "Mirroring started" else "Mirroring stopped")
    if (!running) session?.finish(null)
  }

  override fun onConnectionLost(reason: Int) {
    session?.finish(MirrorServer.EndReason.CONNECTION_LOST)
  }

  override fun onVideoFormat(h265: Boolean, width: Int, height: Int) {
    Log.i(TAG, "Video: ${if (h265) "H.265" else "H.264"}, ${width}x$height")
    session?.startVideo(StreamConfig(if (h265) Codecs.HEVC else Codecs.H264, width, height, MirrorServer.MAX_FPS))
  }

  override fun onVideo(nals: ByteArray, h265: Boolean, nalCount: Int, senderTimeNs: Long) {
    session?.video(nals, h265, senderTimeNs / 1000)
  }

  override fun onAudioFormat(compressionType: Int, samplesPerFrame: Int, usingScreen: Boolean) {
    Log.i(TAG, "Sound: type $compressionType, $samplesPerFrame samples per frame${if (usingScreen) ", mirroring" else ""}")
    session?.startAudio(compressionType)
  }

  override fun onAudio(frame: ByteArray, compressionType: Int, senderTimeNs: Long) {
    session?.audio(frame, senderTimeNs / 1000)
  }

  /**
   * One Apple device on the screen. UxPlay calls in on several threads: video on one, sound on
   * another, the rest on its connection threads.
   */
  private inner class Session(val name: String, override val senderId: String) : Screen.Occupant, VideoDecoder.Listener {
    private val stats = PlaybackStats()
    private val videoLock = Any()
    private val audioLock = Any()
    private var decoder: VideoDecoder? = null
    @Volatile private var config: StreamConfig? = null
    @Volatile private var audio: AudioPlayer? = null
    private val clock = PlaybackClock { audio?.audibleOffsetNs }
    private val ended = CountDownLatch(1)
    @Volatile private var finished = false

    /** Set by the decoder's thread when the decoder broke; the video thread starts a new one. */
    @Volatile private var decoderFailed = false

    /** Once a second, as a phone's pings do: the statistics and the content's frame rate. */
    private val reporter: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "AirPlayStats") }
      .apply { scheduleWithFixedDelay(::report, 1, 1, TimeUnit.SECONDS) }

    fun startVideo(newConfig: StreamConfig) {
      synchronized(videoLock) {
        if (finished) return
        decoderFailed = false
        decoder?.release()
        decoder = null
        config = newConfig
        val surface = awaitSurface()
        if (surface == null) {
          Log.w(TAG, "The TV screen isn't ready")
          server?.dropClient()
          finish(MirrorServer.EndReason.SCREEN_NOT_READY)
          return
        }
        decoder = VideoDecoder(surface, newConfig, clock, stats, this)
      }
      events.onVideoSize(VideoSize(newConfig.width, newConfig.height))
    }

    fun video(nals: ByteArray, h265: Boolean, ptsUs: Long) {
      synchronized(videoLock) {
        // UxPlay marks video it couldn't decrypt by making its first byte 1.
        if (finished || nals.isEmpty() || nals[0].toInt() != 0) return
        if (decoderFailed) restartDecoder()
        val target = decoder ?: return
        val split = NalUnits.split(nals, h265)
        split.parameterSets?.let { target.submit(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, ptsUs, it)) }
        val picture = split.picture ?: return
        val now = System.nanoTime()
        clock.onVideoArrival(ptsUs, now)
        stats.onVideoArrival(ptsUs, picture.size, now)
        target.submit(Frame(FrameType.VIDEO, if (split.keyframe) FrameFlags.KEYFRAME else 0, ptsUs, picture))
      }
    }

    /**
     * A new decoder after the old one broke, on the video thread. It starts with the next keyframe;
     * without a surface (the TV app was left) there is none until the session ends.
     */
    private fun restartDecoder() {
      decoderFailed = false
      decoder?.release()
      decoder = null
      val current = config ?: return
      decoder = videoSurface()?.takeIf { it.isValid }?.let { VideoDecoder(it, current, clock, stats, this) }
    }

    fun startAudio(compressionType: Int) {
      synchronized(audioLock) {
        if (finished) return
        audio?.release()
        audio = null
        val codecConfig = when (compressionType) {
          AAC_ELD -> AAC_ELD_CONFIG
          AAC_LC -> AAC_LC_CONFIG
          else -> {
            Log.w(TAG, "Can't play sound of type $compressionType, continuing without sound")
            return
          }
        }
        audio = try {
          AudioPlayer(AudioConfig(Codecs.AAC, SAMPLE_RATE, CHANNELS), stats).apply {
            submit(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0, codecConfig))
          }
        } catch (e: Exception) {
          Log.w(TAG, "Can't play this sound, continuing without", e)
          null
        }
      }
    }

    fun audio(frame: ByteArray, ptsUs: Long) {
      synchronized(audioLock) {
        // Sound from before UxPlay synced with the sender's clock has no time yet.
        if (finished || ptsUs <= 0) return
        audio?.submit(Frame(FrameType.AUDIO, 0, ptsUs, frame))
      }
    }

    private fun report() {
      if (finished) return
      val shown = synchronized(videoLock) { decoder?.renderOffsetNs }
      val audible = audio?.audibleOffsetNs
      val snapshot = stats.snapshot(
        video = config?.let { VideoSize(it.width, it.height) },
        codec = config?.codec,
        contentRate = clock.steadyFrameRate(),
        // Without pings there is no measure of the sender's clock, so no delay behind it.
        delayNs = null,
        roundTripUs = null,
        skewNs = if (shown != null && audible != null) audible - shown else null,
      )
      events.onContentFrameRate(clock.steadyFrameRate())
      events.onStats(snapshot)
    }

    /** A session that never got the screen: only its statistics timer to stop. */
    fun discard() {
      finished = true
      reporter.shutdownNow()
      ended.countDown()
    }

    /**
     * The session is over: frees the decoder and the screen, and says why when the screen goes idle.
     * Safe to call more than once, from any thread.
     */
    fun finish(reason: MirrorServer.EndReason?) {
      synchronized(this) {
        if (finished) return
        finished = true
      }
      reporter.shutdownNow()
      synchronized(videoLock) {
        decoder?.release()
        decoder = null
      }
      synchronized(audioLock) {
        audio?.release()
        audio = null
      }
      if (session === this) session = null
      if (screen.release(this)) {
        Log.i(TAG, "$name stopped mirroring")
        events.onDisconnected(reason)
      }
      ended.countDown()
    }

    /** AirPlay has no goodbye with a reason: the Apple device sees its connection end. */
    override fun end(byeReason: String) {
      Log.i(TAG, "Ending $name's mirroring: $byeReason")
      server?.dropClient()
      Thread({ finish(null) }, "AirPlayEnd").start()
    }

    override fun close() = end(ByeReasons.REPLACED)

    override fun awaitEnd(): Boolean = ended.await(END_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    /** Waits for the screen's surface, which takes a moment when connecting has just opened the TV app. */
    private fun awaitSurface(): Surface? {
      repeat(SURFACE_WAIT_MS / 50) {
        videoSurface()?.takeIf { it.isValid }?.let { return it }
        Thread.sleep(50)
      }
      return null
    }

    /**
     * The picture's real size. UxPlay reports the size only once, but an iPad that turns sends
     * its new size in the stream; without this, a portrait picture would show stretched.
     */
    override fun onPictureSize(size: VideoSize) {
      val current = config ?: return
      if (finished || (size.width == current.width && size.height == current.height)) return
      Log.i(TAG, "Picture size now ${size.width}x${size.height}")
      config = current.copy(width = size.width, height = size.height)
      events.onVideoSize(size)
    }

    /** AirPlay can't ask for a keyframe; Apple devices send one every few seconds anyway. */
    override fun onNeedKeyframe() = Unit

    override fun onDecoderError(error: Exception) {
      Log.w(TAG, "Decoder error, restarting", error)
      decoderFailed = true
    }
  }

  private companion object {
    const val TAG = "BeamlineAirPlay"
    val AIRPLAY_TYPES = listOf("_airplay._tcp", "_raop._tcp")
    const val SURFACE_WAIT_MS = 4_000
    const val END_TIMEOUT_MS = 3_000L

    // AirPlay's compression types ("ct") and their AudioSpecificConfig, 44.1 kHz stereo.
    const val AAC_LC = 4
    const val AAC_ELD = 8
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2

    /** AAC-LC: object type 2, frequency index 4 (44.1 kHz), 2 channels. */
    val AAC_LC_CONFIG = byteArrayOf(0x12, 0x10)

    /** AAC-ELD: object type 39, 44.1 kHz, 2 channels, 480 samples per frame, as UxPlay's own player sets it up. */
    val AAC_ELD_CONFIG = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)

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
