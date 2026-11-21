package io.github.lumpilabs.beamline.receiver

import java.io.FilterInputStream
import java.net.Socket
import java.net.SocketException

/**
 * Reads [socket] no faster than [limitKbps] allows, or at full speed while it is 0. For testing how
 * a sender copes with a weak Wi-Fi: TCP slows the sender down to the same rate.
 */
class ThrottledInputStream(private val socket: Socket, private val limitKbps: () -> Int) :
  FilterInputStream(socket.getInputStream()) {
  private var windowStartNs = System.nanoTime()
  private var windowBytes = 0L
  private var throttled = false

  override fun read(): Int = super.read().also { if (it >= 0) pace(1) }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    val limit = limitKbps()
    // Small reads keep the pace even, instead of a burst and then a long pause.
    val chunk = if (limit > 0) minOf(len, CHUNK_BYTES) else len
    return super.read(b, off, chunk).also { if (it > 0) pace(it) }
  }

  private fun pace(bytes: Int) {
    val limit = limitKbps()
    setThrottled(limit > 0)
    if (limit <= 0) {
      windowBytes = 0
      windowStartNs = System.nanoTime()
      return
    }
    windowBytes += bytes
    val dueNs = windowStartNs + windowBytes * 8 * 1_000_000 / limit
    val waitNs = dueNs - System.nanoTime()
    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
    // A fresh window now and then, so a pause doesn't buy a burst later.
    if (System.nanoTime() - windowStartNs > WINDOW_NS) {
      windowBytes = 0
      windowStartNs = System.nanoTime()
    }
  }

  /**
   * A small receive buffer while throttled. The system's own buffer, megabytes large, would
   * otherwise take in many seconds of video before the phone notices anything, where a weak Wi-Fi
   * holds it back at once. Nothing changes while the limit stays 0, as it does outside of tests.
   */
  private fun setThrottled(on: Boolean) {
    if (on == throttled) return
    throttled = on
    try {
      socket.receiveBufferSize = if (on) THROTTLED_BUFFER_BYTES else FULL_SPEED_BUFFER_BYTES
    } catch (_: SocketException) {
      // The session is ending.
    }
  }

  private companion object {
    const val CHUNK_BYTES = 4096
    const val WINDOW_NS = 1_000_000_000L
    /** Doubled by Linux: under a tenth of a second at 3 Mbit/s, like an access point's queue. */
    const val THROTTLED_BUFFER_BYTES = 16 * 1024
    const val FULL_SPEED_BUFFER_BYTES = 1024 * 1024
  }
}
