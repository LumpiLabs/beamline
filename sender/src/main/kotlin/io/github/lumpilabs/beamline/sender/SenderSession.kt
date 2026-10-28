package io.github.lumpilabs.beamline.sender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
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
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Control
import io.github.lumpilabs.beamline.protocol.ControlCodec
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
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
import io.github.lumpilabs.beamline.protocol.StreamSizing
import io.github.lumpilabs.beamline.protocol.VideoSize
import io.github.lumpilabs.beamline.protocol.Welcome
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * One mirroring session: connects to [target], captures the screen through [projection] and
 * streams H.264 until either side stops. Capture and encoder changes run on one handler thread.
 */
class SenderSession(
  context: Context,
  private val projection: MediaProjection,
  private val target: ReceiverAddress,
  private val onEnded: () -> Unit,
) {
  private val appContext = context.applicationContext
  private val displayManager = appContext.getSystemService(DisplayManager::class.java)
  private val handlerThread = HandlerThread("beamline-sender").also { it.start() }
  private val handler = Handler(handlerThread.looper)
  private val stopped = AtomicBoolean(false)

  private var socket: Socket? = null
  private lateinit var writer: FrameWriter
  private lateinit var reader: FrameReader
  private lateinit var welcome: Welcome

  // Touched only on the handler thread.
  private var virtualDisplay: VirtualDisplay? = null
  private var encoder: ScreenEncoder? = null
  private var currentSize: VideoSize? = null
  private var capturedContentSize: VideoSize? = null
  private var audio: AudioCapture? = null

  @Volatile private var lastCodecConfig: ByteArray? = null

  fun start() {
    MirrorState.set(appContext, MirrorStatus.Connecting(target.name))
    thread(name = "beamline-connect", isDaemon = true) {
      try {
        connect()
        handler.post { startCapture() }
        readControl()
        stop(null)
      } catch (e: IOException) {
        stop(e.message ?: e.javaClass.simpleName)
      } catch (e: RuntimeException) {
        Log.w(TAG, "Session failed", e)
        stop(e.message ?: e.javaClass.simpleName)
      }
    }
  }

  /** Ends the session. A non-null [error] is shown to the user. Safe to call from any thread. */
  fun stop(error: String?) {
    if (stopped.getAndSet(true)) return
    MirrorState.set(appContext, if (error != null) MirrorStatus.Failed(error) else MirrorStatus.Idle)
    runCatching { writer.write(ControlCodec.toFrame(Bye(if (error != null) "error" else "stopped"))) }
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
      runCatching { socket?.close() }
      handlerThread.quitSafely()
      onEnded()
    }
  }

  private fun connect() {
    val newSocket = Socket()
    socket = newSocket
    newSocket.tcpNoDelay = true
    newSocket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
    newSocket.soTimeout = CONNECT_TIMEOUT_MS
    writer = FrameWriter(newSocket.getOutputStream())
    reader = FrameReader(newSocket.getInputStream())
    send(Hello(listOf(Protocol.VERSION), deviceName(), listOf(Codecs.H264), listOf(Codecs.OPUS)))
    welcome = when (val reply = readControlMessage()) {
      is Welcome -> reply
      is Bye -> throw IOException(if (reply.reason == "busy") "The TV is busy with another device" else reply.reason)
      else -> throw ProtocolException("The TV didn't answer the handshake")
    }
    newSocket.soTimeout = 0
  }

  private fun readControlMessage(): Control? {
    val frame = reader.read() ?: return null
    return if (frame.type == FrameType.CONTROL) ControlCodec.decode(frame.body) else null
  }

  private fun readControl() {
    while (!stopped.get()) {
      val frame = reader.read() ?: return
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
        is Bye -> return
        else -> Unit
      }
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
      try {
        send(Ping(nextId++, nowUs()))
      } catch (_: IOException) {
        stop("Connection to the TV lost")
        return
      }
      handler.postDelayed(this, PING_INTERVAL_MS)
    }
  }

  private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000

  private fun startEncoder(size: VideoSize): ScreenEncoder {
    val fps = min(welcome.maxFps, MAX_FPS)
    send(StreamConfig(Codecs.H264, size.width, size.height, fps))
    val newEncoder = ScreenEncoder(size, fps, ScreenEncoder.bitrateFor(size, fps), encoderSink)
    encoder = newEncoder
    currentSize = size
    MirrorState.set(appContext, MirrorStatus.Mirroring(target.name, size))
    return newEncoder
  }

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
    lastCodecConfig?.let { writer.write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, it)) }
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

  private fun send(message: Control) = writer.write(ControlCodec.toFrame(message))

  private val encoderSink = object : ScreenEncoder.Sink {
    override fun onCodecConfig(data: ByteArray) {
      lastCodecConfig = data
      write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, data))
    }

    override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
      write(Frame(FrameType.VIDEO, if (keyframe) FrameFlags.KEYFRAME else 0, ptsUs, data))
    }

    override fun onError(error: Exception) {
      Log.w(TAG, "Encoder failed", error)
      stop(error.message ?: "Encoder error")
    }

    private fun write(frame: Frame) {
      try {
        writer.write(frame)
      } catch (e: IOException) {
        stop(if (stopped.get()) null else "Connection to the TV lost")
      }
    }
  }

  private val audioSink = object : AudioCapture.Sink {
    override fun onAudioConfig(data: ByteArray) = write(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0, data))

    override fun onAudioFrame(data: ByteArray, ptsUs: Long) = write(Frame(FrameType.AUDIO, 0, ptsUs, data))

    override fun onError(error: Exception) {
      Log.w(TAG, "Audio capture failed, continuing without sound", error)
      handler.post {
        audio?.stop()
        audio = null
      }
    }

    private fun write(frame: Frame) {
      try {
        writer.write(frame)
      } catch (_: IOException) {
        stop(if (stopped.get()) null else "Connection to the TV lost")
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
    const val MAX_FPS = 60
  }
}
