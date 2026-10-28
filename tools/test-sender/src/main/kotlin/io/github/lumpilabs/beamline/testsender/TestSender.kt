package io.github.lumpilabs.beamline.testsender

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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Streams a test pattern to a Beamline TV, for testing the TV app without a phone.
 *
 * Usage: `test-sender <host> [port] [seconds]`
 */
fun main(args: Array<String>) {
  val host = args.getOrNull(0) ?: run {
    System.err.println("Usage: test-sender <host> [port] [seconds]")
    exitProcess(2)
  }
  val port = args.getOrNull(1)?.toInt() ?: Protocol.DEFAULT_PORT
  val seconds = args.getOrNull(2)?.toInt() ?: 20
  val size = VideoSize(1280, 720)
  val fps = 30

  print("Encoding test pattern… ")
  val clip = TestPattern.encode(size, fps, frameCount = fps * 2)
  println("${clip.frames.size} frames, ${clip.frames.sumOf { it.data.size } / 1024} KiB")

  Socket().use { socket ->
    socket.tcpNoDelay = true
    socket.connect(InetSocketAddress(host, port), 5_000)
    socket.soTimeout = 5_000
    val writer = FrameWriter(socket.getOutputStream())
    val reader = FrameReader(socket.getInputStream())

    writer.write(ControlCodec.toFrame(Hello(listOf(Protocol.VERSION), "Beamline test sender", listOf(Codecs.H264), emptyList())))
    val welcome = reader.read()?.let { ControlCodec.decode(it.body) } as? Welcome
      ?: error("The TV didn't answer with welcome")
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
    val total = seconds * fps
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
      val due = start + (i + 1) * 1_000_000_000L / fps
      val wait = due - System.nanoTime()
      if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }
    writer.write(ControlCodec.toFrame(Bye("test finished")))
    println("Sent $total frames in ${(System.nanoTime() - start) / 1_000_000} ms")
  }
}
