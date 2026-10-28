package io.github.lumpilabs.beamline.testsender

import io.github.lumpilabs.beamline.protocol.AudioConfig
import io.github.lumpilabs.beamline.protocol.Bye
import io.github.lumpilabs.beamline.protocol.Codecs
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
import io.github.lumpilabs.beamline.protocol.RequestKeyframe
import io.github.lumpilabs.beamline.protocol.StreamConfig
import io.github.lumpilabs.beamline.protocol.VideoSize
import io.github.lumpilabs.beamline.protocol.Welcome
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val USAGE = "Usage: test-sender <host> [port] [seconds] [fps] [sound.ogg] [--hiccup=<second>]"
private const val SENDER_ID = "beamline-test-sender"
private const val HICCUP_MS = 1_000L

/**
 * Streams a test pattern to a Beamline TV, for testing the TV app without a phone. An Ogg Opus
 * file, for example from `ffmpeg -f lavfi -i sine=frequency=440:duration=5 -ac 2 -c:a libopus
 * tone.ogg`, is sent as sound in a loop. `--hiccup=<second>` holds everything back for a second at
 * that point and then sends it at once, like Wi-Fi that stalls for a moment.
 *
 * Usage: `test-sender <host> [port] [seconds] [fps] [sound.ogg] [--hiccup=<second>]`
 */
fun main(args: Array<String>) {
  val positional = args.filterNot { it.startsWith("--") }
  val host = positional.getOrNull(0) ?: run {
    System.err.println(USAGE)
    exitProcess(2)
  }
  val port = positional.getOrNull(1)?.toInt() ?: Protocol.DEFAULT_PORT
  val seconds = positional.getOrNull(2)?.toInt() ?: 20
  val fps = positional.getOrNull(3)?.toInt() ?: 30
  val sound = positional.getOrNull(4)?.let { OggOpus.read(File(it)) }
  val hiccupAt = args.firstOrNull { it.startsWith("--hiccup=") }?.substringAfter('=')?.toInt()
  val size = VideoSize(1280, 720)

  print("Encoding test pattern... ")
  val clip = TestPattern.encode(size, fps, frameCount = fps * 2)
  println("${clip.frames.size} frames, ${clip.frames.sumOf { it.data.size } / 1024} KiB")

  Socket().use { socket ->
    socket.tcpNoDelay = true
    socket.connect(InetSocketAddress(host, port), 5_000)
    socket.soTimeout = 5_000
    val output = HiccupStream(socket.getOutputStream())
    val writer = FrameWriter(output)
    val reader = FrameReader(socket.getInputStream())

    val audioCodecs = if (sound != null) listOf(Codecs.OPUS) else emptyList()
    // A fixed sender id: a second run replaces the first one on the TV, like a reconnecting phone.
    val hello = Hello(listOf(Protocol.VERSION), "Beamline test sender", listOf(Codecs.H264), audioCodecs, SENDER_ID)
    writer.write(ControlCodec.toFrame(hello))
    val welcome = when (val reply = reader.read()?.let { ControlCodec.decode(it.body) }) {
      is Welcome -> reply
      is Bye -> {
        println("The TV declined: ${reply.reason}")
        exitProcess(1)
      }
      else -> error("The TV didn't answer with welcome")
    }
    println("Connected to ${welcome.name} (protocol ${welcome.version}, up to ${welcome.maxWidth}x${welcome.maxHeight}@${welcome.maxFps})")
    socket.soTimeout = 0

    val keyframeRequests = AtomicInteger()
    val pingSentAt = ConcurrentHashMap<Int, Long>()
    thread(isDaemon = true) {
      runCatching {
        while (true) {
          val frame = reader.read() ?: break
          if (frame.type != FrameType.CONTROL) continue
          when (val message = ControlCodec.decode(frame.body)) {
            is RequestKeyframe -> keyframeRequests.incrementAndGet()
            is Pong -> pingSentAt.remove(message.id)?.let {
              println("Round trip: ${(System.nanoTime() - it) / 1_000_000} ms")
            }
            is Bye -> { println("TV said bye: ${message.reason}"); break }
            else -> Unit
          }
        }
      }
    }

    writer.write(ControlCodec.toFrame(StreamConfig(Codecs.H264, size.width, size.height, fps)))
    writer.write(Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, clip.codecConfig))
    val start = System.nanoTime()
    hiccupAt?.let { output.dueNs = start + it * 1_000_000_000L }
    val audio = sound?.takeIf { Codecs.OPUS in welcome.audioCodecs }?.let { opus ->
      writer.write(ControlCodec.toFrame(AudioConfig(Codecs.OPUS, 48_000, opus.channels)))
      writer.write(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0, opus.header))
      thread(isDaemon = true, name = "audio") { runCatching { sendSound(writer, opus, start, seconds) } }
    }
    if (sound != null && audio == null) println("The TV doesn't take Opus sound; sending video only")

    val total = seconds * fps
    try {
      for (i in 0 until total) {
        // Restart the clip at its keyframe when the TV asks for one or the clip loops.
        val clipIndex = if (keyframeRequests.getAndSet(0) > 0) 0 else i % clip.frames.size
        val encoded = clip.frames[clipIndex]
        val ptsUs = i * 1_000_000L / fps
        writer.write(Frame(FrameType.VIDEO, if (encoded.keyframe) FrameFlags.KEYFRAME else 0, ptsUs, encoded.data))
        if (i % fps == 0) {
          val id = i / fps
          pingSentAt[id] = System.nanoTime()
          writer.write(ControlCodec.toFrame(Ping(id, ptsUs)))
        }
        sleepUntil(start + (i + 1) * 1_000_000_000L / fps)
      }
    } catch (e: IOException) {
      println("The TV closed the connection after ${(System.nanoTime() - start) / 1_000_000} ms: ${e.message}")
      exitProcess(1)
    }
    audio?.join()
    writer.write(ControlCodec.toFrame(Bye("test finished")))
    println("Sent $total frames in ${(System.nanoTime() - start) / 1_000_000} ms")
  }
}

/** Sends the sound in a loop, each packet when it is due, on the same clock as the video. */
private fun sendSound(writer: FrameWriter, opus: OggOpus, start: Long, seconds: Int) {
  var ptsUs = 0L
  var index = 0
  while (ptsUs < seconds * 1_000_000L) {
    val packet = opus.packets[index++ % opus.packets.size]
    writer.write(Frame(FrameType.AUDIO, 0, ptsUs, packet))
    ptsUs += OggOpus.durationUs(packet)
    sleepUntil(start + ptsUs * 1000)
  }
}

/**
 * Once [dueNs] has passed, blocks the next write for [HICCUP_MS]. The frame writer holds its lock
 * meanwhile, so video and sound both stall and then catch up in one burst.
 */
private class HiccupStream(private val out: OutputStream) : FilterOutputStream(out) {
  @Volatile var dueNs = Long.MAX_VALUE

  override fun write(b: ByteArray, off: Int, len: Int) {
    if (System.nanoTime() >= dueNs) {
      dueNs = Long.MAX_VALUE
      println("Holding everything back for $HICCUP_MS ms")
      Thread.sleep(HICCUP_MS)
    }
    out.write(b, off, len)
  }
}

private fun sleepUntil(dueNs: Long) {
  val wait = dueNs - System.nanoTime()
  if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
}
