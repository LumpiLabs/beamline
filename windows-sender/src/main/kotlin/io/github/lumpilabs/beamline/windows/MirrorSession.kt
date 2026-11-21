package io.github.lumpilabs.beamline.windows

import io.github.lumpilabs.beamline.protocol.AudioConfig
import io.github.lumpilabs.beamline.protocol.BitrateController
import io.github.lumpilabs.beamline.protocol.Bye
import io.github.lumpilabs.beamline.protocol.ByeReasons
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Control
import io.github.lumpilabs.beamline.protocol.ControlCodec
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
import io.github.lumpilabs.beamline.protocol.FrameReader
import io.github.lumpilabs.beamline.protocol.FrameType
import io.github.lumpilabs.beamline.protocol.FrameWriter
import io.github.lumpilabs.beamline.protocol.Hello
import io.github.lumpilabs.beamline.protocol.Identity
import io.github.lumpilabs.beamline.protocol.Pairing
import io.github.lumpilabs.beamline.protocol.PairingCode
import io.github.lumpilabs.beamline.protocol.PairingConfirm
import io.github.lumpilabs.beamline.protocol.Ping
import io.github.lumpilabs.beamline.protocol.Pong
import io.github.lumpilabs.beamline.protocol.Protocol
import io.github.lumpilabs.beamline.protocol.ProtocolException
import io.github.lumpilabs.beamline.protocol.RequestKeyframe
import io.github.lumpilabs.beamline.protocol.SendQueue
import io.github.lumpilabs.beamline.protocol.StreamConfig
import io.github.lumpilabs.beamline.protocol.StreamSizing
import io.github.lumpilabs.beamline.protocol.Tls
import io.github.lumpilabs.beamline.protocol.VideoSize
import io.github.lumpilabs.beamline.protocol.Welcome
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/** Where a session stands, for the window and the console. */
sealed interface MirrorStatus {
  data object Idle : MirrorStatus

  data class Connecting(val tv: String) : MirrorStatus

  /** The user must say whether [tv] shows [code] too, see [MirrorSession.answerPairing]. */
  data class Pairing(val tv: String, val code: String) : MirrorStatus

  data class AllowOnTv(val tv: String, val code: String) : MirrorStatus

  data class Mirroring(val tv: String, val size: VideoSize, val encoder: String, val hardware: Boolean, val sound: Boolean) : MirrorStatus

  data class Reconnecting(val tv: String) : MirrorStatus

  data class Failed(val message: String) : MirrorStatus

  fun text(): String =
    when (this) {
      Idle -> Text["status_idle"]
      is Connecting -> Text["status_connecting", tv]
      is Pairing -> Text["status_pairing", tv, spaced(code)]
      is AllowOnTv -> Text["status_allow_on_tv", tv]
      is Mirroring -> Text[if (sound) "status_mirroring" else "status_mirroring_silent", tv, size.width, size.height]
      is Reconnecting -> Text["status_reconnecting", tv]
      is Failed -> Text["status_failed", message]
    }

  companion object {
    /** "123456" as "123 456", as the TV shows it. */
    fun spaced(code: String): String = code.chunked(3).joinToString(" ")
  }
}

/**
 * One mirroring session from this computer: connects to [target], captures screen [screen] and the
 * sound, and streams H.264 and Opus until either side stops. It works like the phone's session,
 * with the same protocol, pairing, send queue and bitrate rules.
 *
 * A lost connection doesn't end the session: capture goes on while the link thread reconnects for
 * up to [RECONNECT_WINDOW_MS], and the stream continues at the next keyframe. The TV answers every
 * ping, so [LINK_TIMEOUT_MS] without data from it means the link is gone.
 */
class MirrorSession(
  private val target: TvAddress,
  private val screen: Int,
  /** For the whole session, also after reconnecting: the TV sets itself up for it when connecting. */
  private val lowLatency: Boolean,
  private val settings: Settings,
  private val listener: Listener,
) {
  interface Listener {
    fun onStatus(status: MirrorStatus)

    /** The session is over; [MirrorStatus.Failed] came before when it ended with an error. */
    fun onEnded()
  }

  private val stopped = AtomicBoolean(false)
  private val identity = settings.identity
  private val tls = Tls.context(identity)

  /** The TV's own name once it said it, until then the address it was reached by. */
  @Volatile private var tvName = target.name

  @Volatile private var pendingPairing: CompletableFuture<Boolean>? = null

  /** The connection frames go to; null while (re)connecting, so frames from then are dropped. */
  private val link = AtomicReference<Link?>(null)

  @Volatile private var connectingSocket: Socket? = null

  @Volatile private var welcome: Welcome? = null

  @Volatile private var capture: ScreenCapture? = null

  @Volatile private var streamConfig: StreamConfig? = null

  @Volatile private var lastCodecConfig: ByteArray? = null

  @Volatile private var opusHeader: ByteArray? = null

  @Volatile private var mirroring: MirrorStatus.Mirroring? = null

  /** Follows the network from the first capture on, across reconnects and new sizes. */
  private var bitrate: BitrateController? = null
  private val bitrateLock = Any()

  /** The system send buffer for the current bitrate, see [SendQueue.socketBufferBytes]. */
  @Volatile private var socketBufferBytes = SendQueue.MAX_SOCKET_BUFFER_BYTES

  private var mediaStarted = false

  /** One connection to the TV. Frames go out through [queue], on its own thread. */
  private inner class Link(
    val socket: Socket,
    /** The TCP socket under [socket]'s encryption. */
    val tcp: Socket,
    val writer: FrameWriter,
    val reader: FrameReader,
    val welcome: Welcome,
  ) : SendQueue.Listener {
    val queue = SendQueue(this, if (lowLatency) SendQueue.LOW_LATENCY_MAX_BACKLOG_NS else SendQueue.DEFAULT_MAX_BACKLOG_NS)

    override fun onCongested() = onVideoSkipped()

    override fun onWriteFailed(error: IOException) {
      // Closing wakes the link thread, which reconnects.
      link.compareAndSet(this, null)
      runCatching { socket.close() }
    }
  }

  fun start() {
    setStatus(MirrorStatus.Connecting(tvName))
    thread(name = "beamline-link", isDaemon = true) {
      try {
        runLink()
      } catch (e: RuntimeException) {
        Log.warn("Session failed", e)
        stop(Text["error_unexpected", e.message ?: e.javaClass.simpleName])
      }
    }
  }

  /** The user's answer while [MirrorStatus.Pairing] is showing: true when both codes match. */
  fun answerPairing(matches: Boolean) {
    pendingPairing?.complete(matches)
  }

  /** Ends the session. A non-null [error] is shown to the user. Safe to call from any thread. */
  fun stop(error: String?) {
    if (stopped.getAndSet(true)) return
    pendingPairing?.complete(false)
    setStatus(if (error != null) MirrorStatus.Failed(error) else MirrorStatus.Idle)
    val last = link.getAndSet(null)
    last?.queue?.close()
    val connecting = connectingSocket
    // Writing can block on a dead connection, so the goodbye goes out on its own thread.
    thread(name = "beamline-bye", isDaemon = true) {
      last?.let {
        runCatching { it.writer.write(ControlCodec.toFrame(Bye(if (error != null) "error" else "stopped"))) }
        runCatching { it.socket.close() }
      }
      runCatching { connecting?.close() }
      listener.onEnded()
    }
  }

  val isStopped: Boolean get() = stopped.get()

  /** Connects, reads the TV's messages until the link breaks, and reconnects until the session ends. */
  private fun runLink() {
    var connectedOnce = false
    var lostAtMs = 0L
    while (!stopped.get()) {
      val current = try {
        connect(reconnect = connectedOnce)
      } catch (e: IOException) {
        // A "no" from the TV or to pairing ends the session, also while reconnecting.
        val answered = e is PairingCancelled || e is TvDeclined
        if (!connectedOnce || answered) {
          Log.info("Connecting failed: ${e.message}")
          stop(connectFailure(e))
        } else if (elapsedMs() - lostAtMs > RECONNECT_WINDOW_MS) {
          stop(Text["error_connection_lost", tvName])
        } else {
          Log.info("Reconnect failed: ${e.message}")
          Thread.sleep(RETRY_INTERVAL_MS)
          continue
        }
        return
      }
      current.queue.start(current.writer)
      link.set(current)
      if (stopped.get()) {
        current.queue.close()
        runCatching { current.socket.close() }
        return
      }
      if (connectedOnce) Log.info("Reconnected after ${elapsedMs() - lostAtMs} ms")
      connectedOnce = true
      attach(current)
      val bye = readControl(current)
      link.compareAndSet(current, null)
      current.queue.close()
      runCatching { current.socket.close() }
      if (bye != null || stopped.get()) {
        stop(bye?.let(::byeMessage))
        return
      }
      lostAtMs = elapsedMs()
      setStatus(MirrorStatus.Reconnecting(tvName))
    }
  }

  /** [reconnect]: after a lost connection, see Hello.reconnect. */
  private fun connect(reconnect: Boolean): Link {
    val socket = Socket()
    connectingSocket = socket
    try {
      socket.tcpNoDelay = true
      socket.sendBufferSize = socketBufferBytes
      socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
      socket.soTimeout = CONNECT_TIMEOUT_MS
      val secure = Tls.client(tls, socket, target.host, target.port)
      val receiverCertificate = Tls.peerCertificate(secure)
      val fingerprint = Identity.fingerprintOf(receiverCertificate)
      val known = settings.isPaired(fingerprint)
      val writer = FrameWriter(secure.outputStream)
      val reader = FrameReader(secure.inputStream)
      val hello = Hello(
        listOf(Protocol.VERSION),
        deviceName(),
        listOf(Codecs.H264),
        listOf(Codecs.OPUS),
        settings.senderId,
        pair = !known,
        lowLatency = lowLatency,
        reconnect = reconnect,
      )
      writer.write(ControlCodec.toFrame(hello))
      var reply = readControlMessage(reader)
      if (reply is Pairing) {
        pair(reply, receiverCertificate, known, writer)
        // The TV's user may still have to find the remote and press OK.
        secure.soTimeout = PAIRING_TIMEOUT_MS.toInt()
        reply = readControlMessage(reader)
      } else if (!known && reply is Welcome) {
        // Without comparing codes, this could be anyone pretending to be the TV.
        throw ProtocolException("The TV skipped pairing")
      }
      val welcome = when (reply) {
        is Welcome -> reply
        is Bye -> throw TvDeclined(reply.reason)
        else -> throw ProtocolException("The TV didn't answer the handshake")
      }
      if (!known) settings.rememberPaired(fingerprint)
      secure.soTimeout = LINK_TIMEOUT_MS
      tvName = welcome.name
      return Link(secure, socket, writer, reader, welcome)
    } catch (e: IOException) {
      runCatching { socket.close() }
      throw e
    } finally {
      connectingSocket = null
    }
  }

  /**
   * Pairing: the TV shows the same code. When this computer doesn't know the TV yet, its user must
   * confirm that both codes match; the TV's user may then still have to allow it with OK.
   */
  private fun pair(request: Pairing, receiverCertificate: ByteArray, known: Boolean, writer: FrameWriter) {
    val code = PairingCode.derive(receiverCertificate, identity.certificateDer)
    if (!known) {
      val answer = CompletableFuture<Boolean>()
      pendingPairing = answer
      setStatus(MirrorStatus.Pairing(tvName, code))
      val matches = try {
        answer.get(PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {
        false
      } finally {
        pendingPairing = null
      }
      if (!matches || stopped.get()) {
        runCatching { writer.write(ControlCodec.toFrame(Bye(ByeReasons.PAIRING_REJECTED))) }
        throw PairingCancelled()
      }
    }
    writer.write(ControlCodec.toFrame(PairingConfirm))
    setStatus(if (request.receiverConfirms) MirrorStatus.AllowOnTv(tvName, code) else MirrorStatus.Connecting(tvName))
  }

  /** Why the first connection failed, in words for the user rather than the system's. */
  private fun connectFailure(e: IOException): String =
    when {
      e is PairingCancelled -> Text["error_pairing_cancelled", tvName]
      e is TvDeclined && e.reason == ByeReasons.PAIRING_DECLINED -> Text["error_pairing_declined", tvName]
      e is TvDeclined && e.reason == ByeReasons.BUSY -> Text["error_tv_busy", tvName]
      e is TvDeclined && e.reason == ByeReasons.REPLACED -> Text["error_tv_replaced", tvName]
      e is TvDeclined && e.reason == ByeReasons.OPEN_APP -> Text["error_tv_open_app", tvName]
      e is TvDeclined -> Text["error_tv_declined", tvName, e.reason]
      // A TLS failure means it isn't a Beamline TV, or one with an older app.
      e is ProtocolException || e is SSLException -> Text["error_not_beamline", tvName]
      else -> Text["error_unreachable", tvName]
    }

  /** The TV answered the handshake with `bye`. */
  private class TvDeclined(val reason: String) : IOException("The TV declined: $reason")

  /** The user saw a different code, cancelled, or didn't answer in time. */
  private class PairingCancelled : IOException("Pairing cancelled on this computer")

  /** What to show when the TV ends mirroring with [bye], or null for an ordinary end. */
  private fun byeMessage(bye: Bye): String? =
    when (bye.reason) {
      ByeReasons.APP_CLOSED -> Text["error_tv_app_closed", tvName]
      ByeReasons.NO_SCREEN -> Text["error_tv_no_screen", tvName]
      ByeReasons.REPLACED -> Text["error_tv_replaced", tvName]
      else -> null
    }

  private fun readControlMessage(reader: FrameReader): Control? {
    val frame = reader.read() ?: return null
    return if (frame.type == FrameType.CONTROL) ControlCodec.decode(frame.body) else null
  }

  /**
   * Handles the TV's messages. Returns the TV's bye, or null when the link broke. Once the session
   * is stopped, it doesn't matter which.
   */
  private fun readControl(current: Link): Bye? {
    while (!stopped.get()) {
      val frame = try {
        current.reader.read()
      } catch (e: IOException) {
        if (!stopped.get()) Log.info("Connection lost: ${e.message}")
        return null
      } ?: return null
      if (frame.type != FrameType.CONTROL) continue
      val message = try {
        ControlCodec.decode(frame.body)
      } catch (_: ProtocolException) {
        continue
      }
      when (message) {
        is RequestKeyframe -> sendKeyframe()
        is Ping -> send(Pong(message.id, message.timeUs, nowUs()))
        is Pong -> if (message.id % 10 == 0) Log.info("Round trip ${(nowUs() - message.pingTimeUs) / 1000} ms")
        is Bye -> return message
        else -> Unit
      }
    }
    return null
  }

  /** Starts capture on the first connection; after a reconnect, tells the TV what is coming. */
  private fun attach(current: Link) {
    welcome = current.welcome
    if (!mediaStarted) {
      mediaStarted = true
      thread(name = "beamline-video", isDaemon = true) { runVideo() }
      if (Codecs.OPUS in current.welcome.audioCodecs) {
        thread(name = "beamline-sound", isDaemon = true) { runSound() }
      }
      thread(name = "beamline-ping", isDaemon = true) { runPings() }
      return
    }
    streamConfig?.let(::send)
    mirroring?.let(::setStatus)
    sendKeyframe()
    opusHeader?.let(::sendAudioConfig)
  }

  /** Captures and encodes the screen until the session ends, starting over when its size changes. */
  private fun runVideo() {
    while (!stopped.get()) {
      val current = try {
        openCapture()
      } catch (e: CaptureException) {
        Log.warn("Screen capture failed", e)
        stop(Text["error_capture", e.message])
        return
      }
      try {
        while (!stopped.get()) {
          val length = current.read(READ_TIMEOUT_MS)
          if (length == ScreenCapture.SOURCE_CHANGED) {
            Log.info("The screen changed size")
            break
          }
          if (length > 0) onFrame(current, length)
        }
      } catch (e: CaptureException) {
        Log.warn("Screen capture stopped", e)
        stop(Text["error_capture", e.message])
      } finally {
        capture = null
        current.close()
      }
    }
  }

  private fun openCapture(): ScreenCapture {
    val screens = Screen.list()
    val source = screens.getOrNull(screen) ?: screens.firstOrNull() ?: throw CaptureException("No screen found")
    val welcome = checkNotNull(welcome)
    val size = StreamSizing.fit(source.size, VideoSize(welcome.maxWidth, welcome.maxHeight))
    val fps = min(welcome.maxFps, MAX_FPS)
    val config = StreamConfig(Codecs.H264, size.width, size.height, fps)
    // Control messages overtake waiting video, so frames of the old stream must not follow this.
    link.get()?.queue?.clearVideo()
    send(config)
    streamConfig = config
    lastCodecConfig = null
    val maxBps = bitrateFor(size, fps)
    val bps = synchronized(bitrateLock) { bitrate?.setMax(maxBps) ?: BitrateController(maxBps).also { bitrate = it }.bps }
    fitSocketBuffer(bps)
    val opened = ScreenCapture(source.index, size, fps, bps)
    capture = opened
    Log.info(
      "Capturing screen ${source.index + 1} (${source.width}x${source.height}) at ${size.width}x${size.height}, " +
        "up to $fps fps, %.1f Mbit/s, with ${opened.encoderName}".format(bps / 1e6),
    )
    val status = MirrorStatus.Mirroring(tvName, size, opened.encoderName, opened.hardware, sound = opusHeader != null)
    mirroring = status
    setStatus(status)
    return opened
  }

  /** Sends one encoded frame, with the codec config first whenever it changes. */
  private fun onFrame(current: ScreenCapture, length: Int) {
    H264.parameterSets(current.frame, length)?.let { config ->
      if (!config.contentEquals(lastCodecConfig)) {
        lastCodecConfig = config
        write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, config))
      }
    }
    write(FrameType.VIDEO, if (current.keyframe) FrameFlags.KEYFRAME else 0, current.ptsNs / 1000, current.frame, length)
  }

  /** Sound is optional: without it, or a TV that plays it, video goes on alone. */
  private fun runSound() {
    val sound = try {
      SoundCapture()
    } catch (e: CaptureException) {
      Log.warn("Sound capture unavailable, mirroring without sound", e)
      return
    }
    val encoder = try {
      OpusEncoder()
    } catch (e: Exception) {
      Log.warn("Opus unavailable, mirroring without sound", e)
      sound.close()
      return
    }
    try {
      opusHeader = encoder.header
      sendAudioConfig(encoder.header)
      mirroring?.let { status -> status.copy(sound = true).also { mirroring = it }.let(::setStatus) }
      val pcm = FloatArray(OpusEncoder.FRAME_SIZE * OpusEncoder.CHANNELS)
      var filled = 0
      var framePtsNs = 0L
      while (!stopped.get()) {
        val frames = sound.read(READ_TIMEOUT_MS)
        if (frames == 0) continue
        // The capture keeps the sound continuous; should it jump anyway, the frame starts over.
        if (filled > 0 && abs(sound.ptsNs - (framePtsNs + framesToNs(filled))) > MAX_SOUND_JUMP_NS) filled = 0
        var offset = 0
        while (offset < frames) {
          if (filled == 0) framePtsNs = sound.ptsNs + framesToNs(offset)
          val take = min(OpusEncoder.FRAME_SIZE - filled, frames - offset)
          System.arraycopy(sound.samples, offset * OpusEncoder.CHANNELS, pcm, filled * OpusEncoder.CHANNELS, take * OpusEncoder.CHANNELS)
          filled += take
          offset += take
          if (filled == OpusEncoder.FRAME_SIZE) {
            write(Frame(FrameType.AUDIO, 0, framePtsNs / 1000, encoder.encode(pcm)))
            filled = 0
          }
        }
      }
    } catch (e: CaptureException) {
      Log.warn("Sound capture stopped, continuing without sound", e)
    } finally {
      encoder.close()
      sound.close()
    }
  }

  private fun framesToNs(frames: Int): Long = frames * 1_000_000_000L / OpusEncoder.SAMPLE_RATE

  private fun sendAudioConfig(header: ByteArray) {
    send(AudioConfig(Codecs.OPUS, OpusEncoder.SAMPLE_RATE, OpusEncoder.CHANNELS))
    write(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0, header))
  }

  /** A ping every second measures the round trip and keeps the TV from taking silence for a lost link. */
  private fun runPings() {
    var id = 0
    while (!stopped.get()) {
      send(Ping(id++, nowUs()))
      followNetwork()
      Thread.sleep(PING_INTERVAL_MS)
    }
  }

  /** The clock of pings and pongs: the one video and sound are stamped with. */
  private fun nowUs(): Long = MediaClock.nowNs() / 1000

  private fun elapsedMs(): Long = System.nanoTime() / 1_000_000

  private fun sendKeyframe() {
    lastCodecConfig?.let { write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, it)) }
    capture?.requestKeyframe()
  }

  private fun send(message: Control) = write(ControlCodec.toFrame(message))

  private fun write(frame: Frame) = write(frame.type, frame.flags, frame.ptsUs, frame.body, frame.body.size)

  /**
   * Queues the first [length] bytes of [body] as one frame for the current connection. Without one
   * (while reconnecting) the frame is dropped.
   */
  private fun write(type: FrameType, flags: Int, ptsUs: Long, body: ByteArray, length: Int) {
    link.get()?.queue?.offer(type, flags, ptsUs, body, length)
  }

  /** The send queue skipped video: ask for a keyframe to resume with, and send less. */
  private fun onVideoSkipped() {
    capture?.requestKeyframe()
    synchronized(bitrateLock) { bitrate?.onCongested() }?.let { applyBitrate(it, "video skipped") }
  }

  /** Once a second: lower the bitrate when frames wait to be sent, raise it after calm stretches. */
  private fun followNetwork() {
    val waitNs = link.get()?.queue?.takeLongestWaitNs() ?: return
    synchronized(bitrateLock) { bitrate?.onLongestWait(waitNs) }?.let {
      applyBitrate(it, "frames waited up to ${waitNs / 1_000_000} ms")
    }
  }

  private fun applyBitrate(bps: Int, reason: String) {
    Log.info("Bitrate %.1f Mbit/s: %s".format(bps / 1e6, reason))
    capture?.setBitrate(bps)
    fitSocketBuffer(bps)
  }

  /** The system's send buffer keeps holding about the same time's worth of video at any bitrate. */
  private fun fitSocketBuffer(bps: Int) {
    socketBufferBytes = SendQueue.socketBufferBytes(bps)
    link.get()?.let { current -> runCatching { current.tcp.sendBufferSize = socketBufferBytes } }
  }

  private fun setStatus(status: MirrorStatus) {
    if (stopped.get() && status !is MirrorStatus.Failed && status != MirrorStatus.Idle) return
    listener.onStatus(status)
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val PING_INTERVAL_MS = 1_000L
    const val READ_TIMEOUT_MS = 100

    /** No pong for this long means the connection is dead. */
    const val LINK_TIMEOUT_MS = 5_000

    /** How long the computer keeps trying to reach the TV again after losing the connection. */
    const val RECONNECT_WINDOW_MS = 60_000L

    /** Time for each of the two users to compare the code and answer, as on the TV. */
    const val PAIRING_TIMEOUT_MS = 120_000L
    const val RETRY_INTERVAL_MS = 1_000L
    const val MAX_FPS = 60
    const val MAX_SOUND_JUMP_NS = 5_000_000L

    /** About 1/16 bit per pixel per frame, between 2 and 12 Mbit/s, as on the phone. */
    fun bitrateFor(size: VideoSize, fps: Int): Int = (size.width.toLong() * size.height * fps / 16).toInt().coerceIn(2_000_000, 12_000_000)

    fun deviceName(): String =
      System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Windows")
  }
}
