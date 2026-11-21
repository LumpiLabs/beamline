package io.github.lumpilabs.beamline.windows

import io.github.lumpilabs.beamline.protocol.Protocol
import io.github.lumpilabs.beamline.protocol.StreamSizing
import io.github.lumpilabs.beamline.protocol.VideoSize
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val USAGE =
  "Usage: beamline [--tv=<address>[:port] [--screen=<number>] [--seconds=<n>] [--low-latency]] [--screens]"

/**
 * Beamline for Windows. Without arguments it opens its window. With `--tv` it mirrors from the
 * console instead, asking there whether the pairing codes match, until Ctrl+C or for `--seconds`.
 * `--screens` lists the screens; `--screen` counts from 1, the main screen.
 */
fun main(args: Array<String>) {
  val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
  when {
    "--screens" in options -> listScreens()
    "--record" in options -> record(options)
    "--tv" in options -> exitProcess(mirrorFromConsole(options))
    args.isEmpty() -> SenderWindow.open()
    else -> {
      System.err.println(USAGE)
      exitProcess(2)
    }
  }
}

private fun listScreens() {
  for (screen in Screen.list()) {
    val key = if (screen.primary) "screen_main" else "screen_item"
    println(Text[key, screen.index + 1, screen.width, screen.height])
  }
}

/**
 * For checking capture without a TV: encodes the screen into an H.264 file at the size and rate a
 * 1080p TV would get, and counts the sound that came in.
 */
private fun record(options: Map<String, String>) {
  val file = File(options.getValue("--record"))
  val screen = Screen.list()[(options["--screen"]?.toIntOrNull() ?: 1) - 1]
  val seconds = options["--seconds"]?.toLongOrNull() ?: 5
  val size = StreamSizing.fit(screen.size, VideoSize(1920, 1080))
  val soundFrames = AtomicLong()
  var peak = 0f
  val sound = SoundCapture()
  val soundThread = thread {
    val end = System.nanoTime() + seconds * 1_000_000_000
    while (System.nanoTime() < end) {
      val frames = sound.read(100)
      soundFrames.addAndGet(frames.toLong())
      for (i in 0 until frames * SoundCapture.CHANNELS) peak = maxOf(peak, abs(sound.samples[i]))
    }
  }
  ScreenCapture(screen.index, size, 30, 8_000_000).use { capture ->
    println("Recording ${size.width}x${size.height} with ${capture.encoderName} (hardware: ${capture.hardware})")
    file.outputStream().buffered().use { out ->
      var frames = 0
      var keyframes = 0
      var firstNs = 0L
      var lastNs = 0L
      val end = System.nanoTime() + seconds * 1_000_000_000
      while (System.nanoTime() < end) {
        val length = capture.read(100)
        if (length <= 0) continue
        if (frames == 0) firstNs = capture.ptsNs
        lastNs = capture.ptsNs
        frames++
        if (capture.keyframe) keyframes++
        out.write(capture.frame, 0, length)
      }
      println("$frames frames ($keyframes keyframes) over ${(lastNs - firstNs) / 1_000_000} ms, latest ${(MediaClock.nowNs() - lastNs) / 1_000_000} ms ago")
    }
  }
  soundThread.join()
  sound.close()
  val peakDb = if (peak > 0) "%.1f dBFS".format(20 * log10(peak)) else "silence"
  println("Sound: ${soundFrames.get()} frames, ${soundFrames.get() * 1000 / SoundCapture.SAMPLE_RATE} ms, peak $peakDb")
}

private fun mirrorFromConsole(options: Map<String, String>): Int {
  val address = options.getValue("--tv")
  val host = address.substringBeforeLast(':').takeIf { ':' in address } ?: address
  val port = address.substringAfterLast(':', "").toIntOrNull() ?: Protocol.DEFAULT_PORT
  val screen = (options["--screen"]?.toIntOrNull() ?: 1) - 1
  val seconds = options["--seconds"]?.toLongOrNull()
  val settings = Settings.load()
  val ended = CountDownLatch(1)
  var failed = false
  lateinit var session: MirrorSession
  session = MirrorSession(
    TvAddress(host, port),
    screen,
    lowLatency = "--low-latency" in options,
    settings,
    object : MirrorSession.Listener {
      override fun onStatus(status: MirrorStatus) {
        println(status.text())
        if (status is MirrorStatus.Failed) failed = true
        if (status is MirrorStatus.Pairing) {
          thread(isDaemon = true) {
            print("${Text["pairing_question", status.tv]} ${MirrorStatus.spaced(status.code)} [y/N] ")
            System.out.flush()
            val answer = readlnOrNull()?.trim()?.lowercase()
            session.answerPairing(answer == "y" || answer == "j")
          }
        }
      }

      override fun onEnded() = ended.countDown()
    },
  )
  Runtime.getRuntime().addShutdownHook(
    thread(start = false) {
      session.stop(null)
      ended.await(2, TimeUnit.SECONDS)
    },
  )
  session.start()
  if (seconds != null && !ended.await(seconds, TimeUnit.SECONDS)) session.stop(null)
  ended.await()
  return if (failed) 1 else 0
}
