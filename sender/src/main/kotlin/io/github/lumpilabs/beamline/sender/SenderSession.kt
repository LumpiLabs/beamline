package io.github.lumpilabs.beamline.sender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.lumpilabs.beamline.protocol.AudioConfig
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
import io.github.lumpilabs.beamline.protocol.StreamConfig
import io.github.lumpilabs.beamline.protocol.StreamSizing
import io.github.lumpilabs.beamline.protocol.Tls
import io.github.lumpilabs.beamline.protocol.VideoSize
import io.github.lumpilabs.beamline.protocol.Welcome
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * One mirroring session: connects to [target], captures the screen through [projection] and
 * streams H.264 and Opus until either side stops. Capture and encoder changes run on one handler
 * thread.
 *
 * A lost connection doesn't end the session: capture goes on while the link thread reconnects for
 * up to [RECONNECT_WINDOW_MS], and the stream continues at the next keyframe. The TV answers every
 * ping, so [LINK_TIMEOUT_MS] without data from it means the link is gone even when the socket
 * hasn't noticed, for example after a Wi-Fi drop.
 *
 * Connections go over Wi-Fi when the phone has it. Otherwise Android may send them over mobile
 * data, where the TV can't be reached, and while Wi-Fi is down each attempt would hang until it
 * times out instead of going through the moment Wi-Fi is back.
 *
 * Every connection is TLS. The first time with a TV, the user compares the pairing code on both
 * screens (see [pair]); after that the TV is recognised by its certificate.
 */
class SenderSession(
  context: Context,
  private val projection: MediaProjection,
  private val target: ReceiverAddress,
  private val onEnded: () -> Unit,
) {
  private val appContext = context.applicationContext
  private val displayManager = appContext.getSystemService(DisplayManager::class.java)
  private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
  private val handlerThread = HandlerThread("beamline-sender").also { it.start() }
  private val handler = Handler(handlerThread.looper)
  private val stopped = AtomicBoolean(false)
  private val senderId = SenderPrefs.senderId(appContext)
  private val identity = SenderPrefs.identity(appContext)
  private val tls = Tls.context(identity)

  /** The user's answer to "does the TV show the same code?", while that question is open. */
  @Volatile private var pendingPairing: CompletableFuture<Boolean>? = null

  /** The connection frames go to; null while (re)connecting, so frames from then are dropped. */
  private val link = AtomicReference<Link?>(null)

  @Volatile private var connectingSocket: Socket? = null

  /** The phone's Wi-Fi (or Ethernet) network while it has one. */
  @Volatile private var localNetwork: Network? = null

  /**
   * The TV was last reached over [localNetwork], so without it reconnecting waits for Wi-Fi to come
   * back. A TV on the phone's own hotspot is reached without it. Touched only on the link thread.
   */
  private var needsLocalNetwork = false

  // Touched only on the handler thread.
  private lateinit var welcome: Welcome
  private var virtualDisplay: VirtualDisplay? = null
  private var encoder: ScreenEncoder? = null
  private var currentSize: VideoSize? = null
  private var streamConfig: StreamConfig? = null
  private var capturedContentSize: VideoSize? = null
  private var audio: AudioCapture? = null

  /** The HEVC encoder wouldn't start once; H.264 for the rest of the session. */
  private var hevcFailed = false

  @Volatile private var lastCodecConfig: ByteArray? = null

  @Volatile private var lastAudioConfig: ByteArray? = null

  private class Link(val socket: Socket, val writer: FrameWriter, val reader: FrameReader, val welcome: Welcome)

  fun start() {
    MirrorState.set(appContext, MirrorStatus.Connecting(target.name))
    // The callback reports networks a moment later; the first connection shouldn't wait for it.
    localNetwork = connectivity.activeNetwork?.takeIf { isLocal(connectivity.getNetworkCapabilities(it)) }
    connectivity.registerNetworkCallback(LOCAL_NETWORKS, networkCallback)
    thread(name = "beamline-link", isDaemon = true) {
      try {
        runLink()
      } catch (e: RuntimeException) {
        Log.w(TAG, "Session failed", e)
        stop(appContext.getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
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
    MirrorState.set(appContext, if (error != null) MirrorStatus.Failed(error) else MirrorStatus.Idle)
    runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    val last = link.getAndSet(null)
    val connecting = connectingSocket
    // Writing can block on a dead connection, so the goodbye goes out on its own thread.
    thread(name = "beamline-bye", isDaemon = true) {
      last?.let {
        runCatching { it.writer.write(ControlCodec.toFrame(Bye(if (error != null) "error" else "stopped"))) }
        runCatching { it.socket.close() }
      }
      runCatching { connecting?.close() }
    }
    handler.post {
      displayManager.unregisterDisplayListener(displayListener)
      audio?.stop()
      audio = null
      encoder?.stop()
      encoder = null
      virtualDisplay?.release()
      virtualDisplay = null
      projection.unregisterCallback(projectionCallback)
      runCatching { projection.stop() }
      handlerThread.quitSafely()
      onEnded()
    }
  }

  /** Connects, reads the TV's messages until the link breaks, and reconnects until the session ends. */
  private fun runLink() {
    var connectedOnce = false
    var lostAtMs = 0L
    while (!stopped.get()) {
      val current = try {
        connect()
      } catch (e: IOException) {
        // A "no" to pairing ends the session, also while reconnecting: asking again won't help.
        val pairingFailed = e is PairingCancelled || (e is TvDeclined && e.reason == ByeReasons.PAIRING_DECLINED)
        if (!connectedOnce || pairingFailed) {
          Log.i(TAG, "Connecting failed: ${e.message}")
          stop(connectFailure(e))
        } else if (SystemClock.elapsedRealtime() - lostAtMs > RECONNECT_WINDOW_MS) {
          stop(appContext.getString(R.string.error_connection_lost, target.name))
        } else {
          Log.i(TAG, "Reconnect failed: ${e.message}")
          Thread.sleep(RETRY_INTERVAL_MS)
          continue
        }
        return
      }
      link.set(current)
      if (stopped.get()) {
        runCatching { current.socket.close() }
        return
      }
      if (connectedOnce) Log.i(TAG, "Reconnected after ${SystemClock.elapsedRealtime() - lostAtMs} ms")
      connectedOnce = true
      handler.post { attach(current) }
      val bye = readControl(current)
      link.compareAndSet(current, null)
      runCatching { current.socket.close() }
      if (bye != null || stopped.get()) {
        stop(bye?.let(::byeMessage))
        return
      }
      lostAtMs = SystemClock.elapsedRealtime()
      MirrorState.set(appContext, MirrorStatus.Reconnecting(target.name))
    }
  }

  private fun connect(): Link {
    val network = localNetwork
    if (network == null && needsLocalNetwork) throw IOException("No Wi-Fi")
    val socket = Socket()
    connectingSocket = socket
    try {
      network?.bindSocket(socket)
      socket.tcpNoDelay = true
      socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
      socket.soTimeout = CONNECT_TIMEOUT_MS
      val secure = Tls.client(tls, socket, target.host, target.port)
      val receiverCertificate = Tls.peerCertificate(secure)
      val fingerprint = Identity.fingerprintOf(receiverCertificate)
      val known = SenderPrefs.isPairedReceiver(appContext, fingerprint)
      val writer = FrameWriter(secure.outputStream)
      val reader = FrameReader(secure.inputStream)
      val hello = Hello(listOf(Protocol.VERSION), deviceName(), listOf(Codecs.H264), listOf(Codecs.OPUS), senderId, pair = !known)
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
      if (!known) SenderPrefs.rememberPairedReceiver(appContext, fingerprint)
      secure.soTimeout = LINK_TIMEOUT_MS
      needsLocalNetwork = network != null
      return Link(secure, writer, reader, welcome)
    } catch (e: IOException) {
      runCatching { socket.close() }
      throw e
    } finally {
      connectingSocket = null
    }
  }

  /**
   * Pairing: the TV shows the same code. When this phone doesn't know the TV yet, its user must
   * confirm that both codes match; the TV's user may then still have to allow the phone with OK.
   */
  private fun pair(request: Pairing, receiverCertificate: ByteArray, known: Boolean, writer: FrameWriter) {
    val code = PairingCode.derive(receiverCertificate, identity.certificateDer)
    if (!known) {
      val answer = CompletableFuture<Boolean>()
      pendingPairing = answer
      MirrorState.set(appContext, MirrorStatus.Pairing(target.name, code))
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
    val next = if (request.receiverConfirms) MirrorStatus.AllowOnTv(target.name, code) else MirrorStatus.Connecting(target.name)
    MirrorState.set(appContext, next)
  }

  /** Why the first connection failed, in words for the user rather than the system's. */
  private fun connectFailure(e: IOException): String =
    when {
      e is PairingCancelled -> appContext.getString(R.string.error_pairing_cancelled, target.name)
      e is TvDeclined && e.reason == ByeReasons.PAIRING_DECLINED -> appContext.getString(R.string.error_pairing_declined, target.name)
      e is TvDeclined && e.reason == ByeReasons.BUSY -> appContext.getString(R.string.error_tv_busy, target.name)
      e is TvDeclined && e.reason == ByeReasons.OPEN_APP -> appContext.getString(R.string.error_tv_open_app, target.name)
      e is TvDeclined -> appContext.getString(R.string.error_tv_declined, target.name, e.reason)
      // A TLS failure means it isn't a Beamline TV, or one with an older app.
      e is ProtocolException || e is SSLException -> appContext.getString(R.string.error_not_beamline, target.name)
      else -> appContext.getString(R.string.error_unreachable, target.name)
    }

  /** The TV answered the handshake with `bye`. */
  private class TvDeclined(val reason: String) : IOException("The TV declined: $reason")

  /** The user saw a different code, cancelled, or didn't answer in time. */
  private class PairingCancelled : IOException("Pairing cancelled on the phone")

  /** What to show when the TV ends mirroring with [bye], or null for an ordinary end. */
  private fun byeMessage(bye: Bye): String? =
    when (bye.reason) {
      ByeReasons.APP_CLOSED -> appContext.getString(R.string.error_tv_app_closed, target.name)
      ByeReasons.NO_SCREEN -> appContext.getString(R.string.error_tv_no_screen, target.name)
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
        if (!stopped.get()) Log.i(TAG, "Connection lost: ${e.message}")
        return null
      } ?: return null
      if (frame.type != FrameType.CONTROL) continue
      val message = try {
        ControlCodec.decode(frame.body)
      } catch (_: ProtocolException) {
        continue
      }
      when (message) {
        is RequestKeyframe -> handler.post { sendKeyframe() }
        is Ping -> send(Pong(message.id, message.timeUs, nowUs()))
        is Pong -> if (message.id % 10 == 0) Log.i(TAG, "Round trip ${(nowUs() - message.pingTimeUs) / 1000} ms")
        is Bye -> return message
        else -> Unit
      }
    }
    return null
  }

  /** Starts capture on the first connection; after a reconnect, tells the TV what is coming. */
  private fun attach(current: Link) {
    if (stopped.get() || link.get() !== current) return
    welcome = current.welcome
    if (virtualDisplay == null) {
      startCapture()
      return
    }
    val size = currentSize
    if (size == null || targetSize() != size) {
      onSourceChanged()
    } else {
      streamConfig?.let {
        send(it)
        MirrorState.set(appContext, MirrorStatus.Mirroring(target.name, size, it.codec))
      }
      sendKeyframe()
    }
    if (audio == null) {
      startAudio()
    } else if (Codecs.OPUS in welcome.audioCodecs) {
      send(AudioConfig(Codecs.OPUS, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS))
      lastAudioConfig?.let { write(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0, it)) }
    }
  }

  private fun startCapture() {
    if (stopped.get()) return
    projection.registerCallback(projectionCallback, handler)
    displayManager.registerDisplayListener(displayListener, handler)
    val size = targetSize()
    val newEncoder = startEncoder(size)
    virtualDisplay = projection.createVirtualDisplay(
      "Beamline",
      size.width,
      size.height,
      appContext.resources.displayMetrics.densityDpi,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      newEncoder.inputSurface,
      null,
      handler,
    )
    startAudio()
    handler.post(pingTask)
  }

  /** Sound is optional: without the permission, an Opus encoder or a TV that plays it, video goes on alone. */
  private fun startAudio() {
    if (Codecs.OPUS !in welcome.audioCodecs) return
    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(TAG, "No audio permission, mirroring without sound")
      return
    }
    try {
      send(AudioConfig(Codecs.OPUS, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS))
      audio = AudioCapture(projection, audioSink)
    } catch (e: Exception) {
      Log.w(TAG, "Audio capture unavailable, mirroring without sound", e)
      audio = null
    }
  }

  /**
   * Some encoders (for example on Samsung phones) don't repeat frames on a still screen. A ping
   * every second keeps the TV from treating the silence as a lost connection, and measures the
   * round trip.
   */
  private val pingTask = object : Runnable {
    private var nextId = 0

    override fun run() {
      if (stopped.get()) return
      send(Ping(nextId++, nowUs()))
      handler.postDelayed(this, PING_INTERVAL_MS)
    }
  }

  /**
   * The clock of pings and pongs: the one video and sound are stamped with, so the TV can tell from
   * the round trips how far behind the phone its picture is.
   */
  private fun nowUs(): Long = System.nanoTime() / 1000

  private fun startEncoder(size: VideoSize): ScreenEncoder {
    val fps = min(welcome.maxFps, MAX_FPS)
    val codec = if (useHevc(size, fps)) Codecs.HEVC else Codecs.H264
    val config = StreamConfig(codec, size.width, size.height, fps)
    send(config)
    val newEncoder = try {
      ScreenEncoder(codec, size, fps, ScreenEncoder.bitrateFor(size, fps), encoderSink)
    } catch (e: Exception) {
      if (codec == Codecs.H264) throw e
      // The new stream config tells the TV to switch its decoder back too.
      Log.w(TAG, "HEVC encoder failed, using H.264", e)
      hevcFailed = true
      return startEncoder(size)
    }
    encoder = newEncoder
    currentSize = size
    streamConfig = config
    MirrorState.set(appContext, MirrorStatus.Mirroring(target.name, size, codec))
    return newEncoder
  }

  /**
   * HEVC gives a sharper picture at the same bitrate, but only when the user switched it on, the TV
   * decodes it and the phone encodes it in hardware at this size.
   */
  private fun useHevc(size: VideoSize, fps: Int): Boolean =
    SenderPrefs.hevc(appContext) &&
      !hevcFailed &&
      Codecs.HEVC in welcome.videoCodecs &&
      ScreenEncoder.hardwareEncoder(Codecs.HEVC, size, fps) != null

  /** Rotation or a resized captured app: restart the encoder at the new size. */
  private fun onSourceChanged() {
    if (stopped.get() || virtualDisplay == null) return
    val size = targetSize()
    if (size == currentSize) return
    virtualDisplay?.surface = null
    encoder?.stop()
    val newEncoder = startEncoder(size)
    virtualDisplay?.resize(size.width, size.height, appContext.resources.displayMetrics.densityDpi)
    virtualDisplay?.surface = newEncoder.inputSurface
  }

  private fun sendKeyframe() {
    lastCodecConfig?.let { write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, it)) }
    encoder?.requestKeyframe()
  }

  private fun targetSize(): VideoSize =
    StreamSizing.fit(capturedContentSize ?: screenSize(), VideoSize(welcome.maxWidth, welcome.maxHeight))

  private fun screenSize(): VideoSize {
    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    val mode = display.mode
    val sideways = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
    return if (sideways) {
      VideoSize(mode.physicalHeight, mode.physicalWidth)
    } else {
      VideoSize(mode.physicalWidth, mode.physicalHeight)
    }
  }

  private fun deviceName(): String =
    Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

  private fun send(message: Control) = write(ControlCodec.toFrame(message))

  private fun write(frame: Frame) = write(frame.type, frame.flags, frame.ptsUs, frame.body, frame.body.size)

  /**
   * Sends the first [length] bytes of [body] as one frame to the current connection. Without one
   * (while reconnecting) the frame is dropped.
   */
  private fun write(type: FrameType, flags: Int, ptsUs: Long, body: ByteArray, length: Int) {
    val current = link.get() ?: return
    try {
      current.writer.write(type, flags, ptsUs, body, length)
    } catch (_: IOException) {
      // Closing wakes the link thread, which reconnects.
      link.compareAndSet(current, null)
      runCatching { current.socket.close() }
    }
  }

  private val encoderSink = object : ScreenEncoder.Sink {
    override fun onCodecConfig(data: ByteArray) {
      lastCodecConfig = data
      write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, data))
    }

    override fun onFrame(data: ByteArray, length: Int, ptsUs: Long, keyframe: Boolean) {
      write(FrameType.VIDEO, if (keyframe) FrameFlags.KEYFRAME else 0, ptsUs, data, length)
    }

    override fun onError(error: Exception) {
      Log.w(TAG, "Encoder failed", error)
      stop(appContext.getString(R.string.error_encoder))
    }
  }

  private val audioSink = object : AudioCapture.Sink {
    override fun onAudioConfig(data: ByteArray) {
      lastAudioConfig = data
      write(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0, data))
    }

    override fun onAudioFrame(data: ByteArray, ptsUs: Long) = write(Frame(FrameType.AUDIO, 0, ptsUs, data))

    override fun onError(error: Exception) {
      Log.w(TAG, "Audio capture failed, continuing without sound", error)
      handler.post {
        audio?.stop()
        audio = null
      }
    }
  }

  private val projectionCallback = object : MediaProjection.Callback() {
    override fun onStop() = stop(null)

    override fun onCapturedContentResize(width: Int, height: Int) {
      if (width > 0 && height > 0) {
        capturedContentSize = VideoSize(width, height)
        onSourceChanged()
      }
    }
  }

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      localNetwork = network
    }

    override fun onLost(network: Network) {
      if (localNetwork == network) localNetwork = null
    }
  }

  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayChanged(displayId: Int) {
      if (displayId == Display.DEFAULT_DISPLAY && capturedContentSize == null) onSourceChanged()
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit
  }

  private companion object {
    const val TAG = "BeamlineSender"
    const val CONNECT_TIMEOUT_MS = 5_000
    const val PING_INTERVAL_MS = 1_000L

    /** No pong for this long means the connection is dead. */
    const val LINK_TIMEOUT_MS = 5_000

    /** How long the phone keeps trying to reach the TV again after losing the connection. */
    const val RECONNECT_WINDOW_MS = 60_000L

    /** Time for each of the two users to compare the code and answer, as on the TV. */
    const val PAIRING_TIMEOUT_MS = 120_000L
    const val RETRY_INTERVAL_MS = 1_000L
    const val MAX_FPS = 60

    /** Wi-Fi and Ethernet, with or without internet access: the networks a TV can be on. */
    val LOCAL_NETWORKS: NetworkRequest = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()

    /** A VPN reports the transports under it, but connections to the TV shouldn't go through it. */
    fun isLocal(capabilities: NetworkCapabilities?): Boolean =
      capabilities != null &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
  }
}
