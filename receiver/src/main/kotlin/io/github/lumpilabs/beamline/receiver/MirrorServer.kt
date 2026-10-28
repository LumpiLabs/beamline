package io.github.lumpilabs.beamline.receiver

import android.util.Log
import android.view.Surface
import androidx.annotation.StringRes
import io.github.lumpilabs.beamline.protocol.AudioConfig
import io.github.lumpilabs.beamline.protocol.Bye
import io.github.lumpilabs.beamline.protocol.ClockSync
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Control
import io.github.lumpilabs.beamline.protocol.ControlCodec
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
import io.github.lumpilabs.beamline.protocol.FrameRateDetector
import io.github.lumpilabs.beamline.protocol.FrameReader
import io.github.lumpilabs.beamline.protocol.FrameType
import io.github.lumpilabs.beamline.protocol.FrameWriter
import io.github.lumpilabs.beamline.protocol.Hello
import io.github.lumpilabs.beamline.protocol.Ping
import io.github.lumpilabs.beamline.protocol.Pong
import io.github.lumpilabs.beamline.protocol.Protocol
import io.github.lumpilabs.beamline.protocol.ProtocolException
import io.github.lumpilabs.beamline.protocol.RequestKeyframe
import io.github.lumpilabs.beamline.protocol.StreamConfig
import io.github.lumpilabs.beamline.protocol.VideoSize
import io.github.lumpilabs.beamline.protocol.Welcome
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Accepts one sender at a time over TCP and plays its video on the surface from [videoSurface].
 * A second sender gets a `bye` with reason "busy", unless it is the same sender reconnecting
 * after losing its connection: then the new connection replaces the old one.
 */
class MirrorServer(
  private val tvName: String,
  private val videoSurface: () -> Surface?,
  private val events: Events,
) {
  interface Events {
    fun onConnected(senderName: String)

    fun onVideoSize(size: VideoSize)

    /** About once a second while streaming: the content's steady frame rate, if any. */
    fun onContentFrameRate(rate: FrameRateDetector.StandardRate?)

    /** About once a second while streaming. */
    fun onStats(stats: StatsSnapshot)

    /** [reason] is null when the sender ended the session itself. */
    fun onDisconnected(reason: EndReason?)
  }

  /** Why a session ended, for the TV's idle screen. */
  enum class EndReason(@StringRes val message: Int) {
    SENDER_SILENT(R.string.end_sender_silent),
    SCREEN_NOT_READY(R.string.end_screen_not_ready),
    CONNECTION_LOST(R.string.end_connection_lost),
    FAILED(R.string.end_failed),
  }

  private class SenderSilentException : IOException("No data from the sender for ${STREAM_TIMEOUT_MS / 1000} s")

  private class ScreenNotReadyException : IOException("The TV screen isn't ready")

  @Volatile private var serverSocket: ServerSocket? = null

  /** What the TV can decode, offered to every sender in `welcome`; looked up once. */
  private val videoCodecs by lazy {
    VideoDecoder.supportedCodecs(MAX_SIZE, MAX_FPS).also { Log.i(TAG, "Decodes $it") }
  }

  private val lock = Any()

  /** The session playing on the screen. Guarded by [lock]. */
  private var active: Session? = null

  /** Starts listening on [Protocol.DEFAULT_PORT], or any free port if that one is taken. */
  fun start(): Int {
    val server = ServerSocket().apply { reuseAddress = true }
    try {
      server.bind(InetSocketAddress(Protocol.DEFAULT_PORT))
    } catch (_: BindException) {
      server.bind(InetSocketAddress(0))
    }
    serverSocket = server
    thread(name = "beamline-accept", isDaemon = true) { acceptLoop(server) }
    return server.localPort
  }

  fun stop() {
    runCatching { serverSocket?.close() }
    serverSocket = null
    synchronized(lock) { active }?.close()
  }

  private fun acceptLoop(server: ServerSocket) {
    while (!server.isClosed) {
      val client = try {
        server.accept()
      } catch (_: IOException) {
        break
      }
      thread(name = "beamline-session", isDaemon = true) { Session(client).run() }
    }
  }

  /**
   * Makes [session] the one on screen. A session from the same sender as the active one replaces
   * it, keeping its statistics; the old one is torn down first so its decoder lets go of the
   * screen. Returns false when the TV is busy with another sender.
   */
  private fun claim(session: Session, senderId: String?): Boolean {
    val previous = synchronized(lock) {
      val current = active
      if (current != null && (senderId == null || current.senderId != senderId)) return false
      active = session
      current
    }
    if (previous != null) {
      Log.i(TAG, "The sender reconnected, replacing its old connection")
      previous.close()
      previous.awaitEnd()
      session.stats = previous.stats.also { it.onReconnect() }
    }
    return true
  }

  /** Returns true when [session] was still the active one, so its end means the screen goes idle. */
  private fun release(session: Session): Boolean =
    synchronized(lock) {
      if (active !== session) return false
      active = null
      true
    }

  private inner class Session(private val socket: Socket) : VideoDecoder.Listener {
    private lateinit var writer: FrameWriter
    private lateinit var reader: FrameReader
    private var decoder: VideoDecoder? = null
    private var audio: AudioPlayer? = null
    private var config: StreamConfig? = null
    private val clock = PlaybackClock { audio?.audibleOffsetNs }
    private val clockSync = ClockSync()
    private var nextPingId = 0
    private var loggedMinute = -1L
    private val ended = CountDownLatch(1)

    @Volatile var senderId: String? = null
      private set

    /** Set before streaming starts; a reconnecting sender's session takes over the old one's. */
    @Volatile var stats = PlaybackStats()

    fun run() {
      var claimed = false
      var reason: EndReason? = null
      try {
        val hello = handshake()
        val version = Protocol.negotiate(hello.versions)
        if (version == null) {
          send(Bye("unsupported protocol version"))
          return
        }
        claimed = claim(this, hello.senderId)
        if (!claimed) {
          send(Bye("busy"))
          return
        }
        send(Welcome(version, tvName, MAX_SIZE.width, MAX_SIZE.height, MAX_FPS, listOf(Codecs.OPUS), videoCodecs))
        events.onConnected(hello.name)
        stream()
      } catch (e: IOException) {
        reason = when (e) {
          is SenderSilentException -> EndReason.SENDER_SILENT
          is ScreenNotReadyException -> EndReason.SCREEN_NOT_READY
          else -> EndReason.CONNECTION_LOST
        }
        Log.i(TAG, "Session ended: ${e.message}")
      } catch (e: RuntimeException) {
        reason = EndReason.FAILED
        Log.w(TAG, "Session failed", e)
      } finally {
        runCatching { socket.close() }
        if (claimed && release(this)) events.onDisconnected(reason)
        ended.countDown()
      }
    }

    fun close() {
      runCatching { socket.close() }
    }

    fun awaitEnd() {
      ended.await(REPLACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun handshake(): Hello {
      socket.tcpNoDelay = true
      socket.soTimeout = HANDSHAKE_TIMEOUT_MS
      writer = FrameWriter(socket.getOutputStream())
      reader = FrameReader(socket.getInputStream())
      val hello = reader.read()?.let(::controlOrNull) as? Hello
        ?: throw ProtocolException("The sender didn't start with hello")
      senderId = hello.senderId
      return hello
    }

    private fun stream() {
      // The sender pings every second even on a still screen, so silence means the link is gone.
      socket.soTimeout = STREAM_TIMEOUT_MS
      try {
        while (true) {
          val frame = try {
            reader.read()
          } catch (_: SocketTimeoutException) {
            throw SenderSilentException()
          } ?: break
          when (frame.type) {
            FrameType.CONTROL -> if (!handleControl(frame)) break
            FrameType.VIDEO -> {
              if (!frame.hasFlag(FrameFlags.CODEC_CONFIG)) {
                val now = System.nanoTime()
                clock.onVideoArrival(frame.ptsUs, now)
                stats.onVideoArrival(frame.ptsUs, frame.body.size, now)
              }
              decoder?.submit(frame)
            }
            FrameType.AUDIO -> audio?.submit(frame)
          }
        }
      } finally {
        decoder?.release()
        decoder = null
        audio?.release()
        audio = null
      }
    }

    /** Returns false when the sender said bye. */
    private fun handleControl(frame: Frame): Boolean {
      when (val message = controlOrNull(frame)) {
        is StreamConfig -> startDecoder(message)
        is AudioConfig -> startAudio(message)
        is Ping -> {
          send(Pong(message.id, message.timeUs, nowUs()))
          // Pinging back tells how far the sender's clock is from the TV's, and so the delay.
          send(Ping(nextPingId++, nowUs()))
          events.onContentFrameRate(clock.steadyFrameRate())
          reportStats()
          if (message.id % 5 == 0) logSync()
        }
        is Pong -> clockSync.add(message.pingTimeUs, nowUs(), message.timeUs)
        is Bye -> return false
        else -> Unit
      }
      return true
    }

    /**
     * Passes the figures on for the statistics overlay, and logs them once a minute. The delay
     * needs the sender's clock, which only senders that answer the TV's pings tell.
     */
    private fun reportStats() {
      val shown = decoder?.renderOffsetNs
      val audible = audio?.audibleOffsetNs
      val offsetNs = clockSync.offsetUs?.times(1000)
      val delayNs = if (shown != null && offsetNs != null) shown - offsetNs else null
      val snapshot = stats.snapshot(
        video = config?.let { VideoSize(it.width, it.height) },
        codec = config?.codec,
        contentRate = clock.steadyFrameRate(),
        delayNs = delayNs?.takeIf { it in 0..MAX_PLAUSIBLE_DELAY_NS },
        roundTripUs = clockSync.roundTripUs,
        skewNs = if (shown != null && audible != null) audible - shown else null,
      )
      events.onStats(snapshot)
      val minute = snapshot.elapsedMs / 60_000
      if (minute != loggedMinute) {
        loggedMinute = minute
        Log.i(TAG, snapshot.summary())
      }
    }

    private fun startAudio(audioConfig: AudioConfig) {
      audio?.release()
      audio = try {
        AudioPlayer(audioConfig, stats)
      } catch (e: Exception) {
        Log.w(TAG, "Can't play this audio, continuing without sound", e)
        null
      }
    }

    /** Positive skew: sound is heard later than the matching picture is shown. */
    private fun logSync() {
      val shown = decoder?.renderOffsetNs ?: return
      val audible = audio?.audibleOffsetNs
      val rate = clock.steadyFrameRate()?.name ?: "varying"
      if (audible == null) {
        Log.i(TAG, "Video paced without sound, content $rate")
      } else {
        Log.i(TAG, "A/V skew ${(audible - shown) / 1_000_000} ms, content $rate")
      }
    }

    private fun startDecoder(newConfig: StreamConfig) {
      decoder?.release()
      decoder = null
      config = newConfig
      val surface = awaitSurface() ?: throw ScreenNotReadyException()
      decoder = VideoDecoder(surface, newConfig, clock, stats, this)
      events.onVideoSize(VideoSize(newConfig.width, newConfig.height))
    }

    private fun awaitSurface(): Surface? {
      repeat(20) {
        videoSurface()?.takeIf { it.isValid }?.let { return it }
        Thread.sleep(50)
      }
      return null
    }

    private fun send(message: Control) = writer.write(ControlCodec.toFrame(message))

    /** The TV's clock for pings and pongs: the one frames are shown by. */
    private fun nowUs(): Long = System.nanoTime() / 1000

    override fun onNeedKeyframe() {
      runCatching { send(RequestKeyframe) }
    }

    override fun onFrameRendered() = Unit

    override fun onDecoderError(error: Exception) {
      Log.w(TAG, "Decoder error, restarting", error)
      thread(name = "beamline-decoder-restart", isDaemon = true) {
        val current = config ?: return@thread
        runCatching {
          startDecoder(current)
          send(RequestKeyframe)
        }
      }
    }
  }

  private fun controlOrNull(frame: Frame): Control? =
    if (frame.type != FrameType.CONTROL) {
      null
    } else {
      try {
        ControlCodec.decode(frame.body)
      } catch (e: ProtocolException) {
        // Unknown message types from newer senders are ignored.
        Log.d(TAG, "Ignoring control message: ${e.message}")
        null
      }
    }

  companion object {
    private const val TAG = "BeamlineServer"
    private const val HANDSHAKE_TIMEOUT_MS = 5_000
    /** The string end_sender_silent names this time too. */
    private const val STREAM_TIMEOUT_MS = 10_000

    /** How long a reconnecting sender waits for its old session to let go of the screen. */
    private const val REPLACE_TIMEOUT_MS = 3_000L

    /** A larger delay means the sender's pongs use another clock than its frames (older apps). */
    private const val MAX_PLAUSIBLE_DELAY_NS = 5_000_000_000L
    const val MAX_FPS = 60

    /** Full HD height; wider than 16:9 so a 20:9 phone in landscape keeps full detail when filled. */
    val MAX_SIZE = VideoSize(2560, 1080)
  }
}
