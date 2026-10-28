package io.github.lumpilabs.beamline.receiver

import android.os.SystemClock
import android.util.Log
import android.view.Surface
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
import io.github.lumpilabs.beamline.protocol.VideoSize
import io.github.lumpilabs.beamline.protocol.Welcome
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Accepts one sender at a time over TCP and plays its video on the surface from [videoSurface].
 * A second sender gets a `bye` with reason "busy".
 */
class MirrorServer(
  private val tvName: String,
  private val videoSurface: () -> Surface?,
  private val events: Events,
) {
  interface Events {
    fun onConnected(senderName: String)

    fun onVideoSize(size: VideoSize)

    fun onDisconnected(reason: String?)
  }

  @Volatile private var serverSocket: ServerSocket? = null

  @Volatile private var activeSocket: Socket? = null

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
    runCatching { activeSocket?.close() }
  }

  private fun acceptLoop(server: ServerSocket) {
    while (!server.isClosed) {
      val client = try {
        server.accept()
      } catch (_: IOException) {
        break
      }
      if (activeSocket != null) {
        thread(name = "beamline-reject", isDaemon = true) { rejectBusy(client) }
        continue
      }
      activeSocket = client
      thread(name = "beamline-session", isDaemon = true) {
        var reason: String? = null
        try {
          Session(client).run()
        } catch (e: IOException) {
          reason = e.message
          Log.i(TAG, "Session ended: ${e.message}")
        } catch (e: RuntimeException) {
          reason = e.message
          Log.w(TAG, "Session failed", e)
        } finally {
          runCatching { client.close() }
          activeSocket = null
          events.onDisconnected(reason)
        }
      }
    }
  }

  private fun rejectBusy(client: Socket) {
    client.use {
      runCatching { FrameWriter(it.getOutputStream()).write(ControlCodec.toFrame(Bye("busy"))) }
    }
  }

  private inner class Session(private val socket: Socket) : VideoDecoder.Listener {
    private lateinit var writer: FrameWriter
    private var decoder: VideoDecoder? = null
    private var audio: AudioPlayer? = null
    private var config: StreamConfig? = null
    private val clock = PlaybackClock { audio?.audibleOffsetNs }

    fun run() {
      socket.tcpNoDelay = true
      socket.soTimeout = HANDSHAKE_TIMEOUT_MS
      val reader = FrameReader(socket.getInputStream())
      writer = FrameWriter(socket.getOutputStream())

      val hello = reader.read()?.let(::controlOrNull) as? Hello
        ?: throw ProtocolException("The sender didn't start with hello")
      val version = Protocol.negotiate(hello.versions)
      if (version == null) {
        send(Bye("unsupported protocol version"))
        return
      }
      send(Welcome(version, tvName, MAX_SIZE.width, MAX_SIZE.height, MAX_FPS, listOf(Codecs.OPUS)))
      events.onConnected(hello.name)

      // The sender pings every second even on a still screen, so silence means the link is gone.
      socket.soTimeout = STREAM_TIMEOUT_MS
      try {
        while (true) {
          val frame = try {
            reader.read()
          } catch (_: SocketTimeoutException) {
            throw IOException("No data from the sender for ${STREAM_TIMEOUT_MS / 1000} s")
          } ?: break
          when (frame.type) {
            FrameType.CONTROL -> if (!handleControl(frame)) break
            FrameType.VIDEO -> {
              if (!frame.hasFlag(FrameFlags.CODEC_CONFIG)) clock.onVideoArrival(frame.ptsUs)
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
          send(Pong(message.id, message.timeUs, SystemClock.elapsedRealtimeNanos() / 1000))
          if (message.id % 5 == 0) logSync()
        }
        is Bye -> return false
        else -> Unit
      }
      return true
    }

    private fun startAudio(audioConfig: AudioConfig) {
      audio?.release()
      audio = try {
        AudioPlayer(audioConfig)
      } catch (e: Exception) {
        Log.w(TAG, "Can't play this audio, continuing without sound", e)
        null
      }
    }

    /** Positive skew: sound is heard later than the matching picture is shown. */
    private fun logSync() {
      val shown = decoder?.renderOffsetNs ?: return
      val audible = audio?.audibleOffsetNs
      if (audible == null) {
        Log.i(TAG, "Video paced without sound")
      } else {
        Log.i(TAG, "A/V skew ${(audible - shown) / 1_000_000} ms")
      }
    }

    private fun startDecoder(newConfig: StreamConfig) {
      decoder?.release()
      decoder = null
      config = newConfig
      val surface = awaitSurface() ?: throw IOException("The TV screen isn't ready")
      decoder = VideoDecoder(surface, newConfig, clock, this)
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

  private companion object {
    const val TAG = "BeamlineServer"
    const val HANDSHAKE_TIMEOUT_MS = 5_000
    const val STREAM_TIMEOUT_MS = 10_000
    const val MAX_FPS = 60
    val MAX_SIZE = VideoSize(1920, 1080)
  }
}
