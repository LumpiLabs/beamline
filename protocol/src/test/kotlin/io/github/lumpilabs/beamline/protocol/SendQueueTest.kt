package io.github.lumpilabs.beamline.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendQueueTest {
  private var nowMs = 0L
  private var congestions = 0
  private val listener = object : SendQueue.Listener {
    override fun onCongested() {
      congestions++
    }

    override fun onWriteFailed(error: IOException) = Unit
  }
  private val queue = SendQueue(listener) { nowMs * 1_000_000 }

  private fun video(pts: Long, flags: Int = 0) = queue.offer(FrameType.VIDEO, flags, pts, byteArrayOf(pts.toByte()), 1)

  private fun keyframe(pts: Long) = video(pts, FrameFlags.KEYFRAME)

  private fun polled(): List<String> =
    generateSequence { queue.poll() }.map { "${it.type} ${it.ptsUs}" }.toList()

  @Test
  fun controlAndSoundGoBeforeWaitingVideo() {
    keyframe(1)
    video(2)
    queue.offer(FrameType.AUDIO, 0, 3, ByteArray(4), 4)
    queue.offer(FrameType.CONTROL, 0, 0, ByteArray(4), 4)
    assertEquals(listOf("AUDIO 3", "CONTROL 0", "VIDEO 1", "VIDEO 2"), polled())
  }

  @Test
  fun framesAreCopied() {
    val reused = byteArrayOf(1, 2, 3)
    queue.offer(FrameType.VIDEO, FrameFlags.KEYFRAME, 1, reused, 2)
    reused[0] = 9
    val item = queue.poll()!!
    assertEquals(listOf<Byte>(1, 2), item.body.take(item.length))
  }

  @Test
  fun backedUpVideoIsThrownAwayUpToTheNextKeyframe() {
    queue.offer(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, ByteArray(8), 8)
    keyframe(1)
    nowMs = 40
    video(2)
    // The network stalls: the keyframe has waited 300 ms when the next frame comes.
    nowMs = 300
    video(3)
    assertEquals(1, congestions)
    assertEquals(3, queue.droppedVideo)
    video(4)
    assertEquals(4, queue.droppedVideo)
    keyframe(5)
    video(6)
    // The codec config stays: the TV's decoder needs it.
    assertEquals(listOf("VIDEO 0", "VIDEO 5", "VIDEO 6"), polled())
    assertEquals(1, congestions)
  }

  @Test
  fun videoThatGoesOutInTimeIsKept() {
    keyframe(1)
    nowMs = 200
    queue.poll()
    video(2)
    nowMs = 400
    video(3)
    assertEquals(0, queue.droppedVideo)
    assertEquals(0, congestions)
  }

  @Test
  fun theLongestWaitCountsFramesStillWaiting() {
    keyframe(1)
    nowMs = 50
    queue.poll()
    assertEquals(50_000_000, queue.takeLongestWaitNs())
    assertEquals(0, queue.takeLongestWaitNs())
    video(2)
    nowMs = 80
    assertEquals(30_000_000, queue.takeLongestWaitNs())
  }

  @Test
  fun aNewStreamDropsTheOldOnesVideo() {
    keyframe(1)
    nowMs = 300
    video(2)
    queue.clearVideo()
    video(3)
    assertEquals(listOf("VIDEO 3"), polled())
  }

  @Test
  fun writesInOrderOnItsThread() {
    val out = ByteArrayOutputStream()
    val live = SendQueue(listener)
    live.offer(FrameType.VIDEO, FrameFlags.KEYFRAME, 1, byteArrayOf(1), 1)
    live.offer(FrameType.VIDEO, 0, 2, byteArrayOf(2), 1)
    live.offer(FrameType.CONTROL, 0, 0, byteArrayOf(3), 1)
    live.start(FrameWriter(out))
    val deadline = System.nanoTime() + 2_000_000_000
    while (out.size() < 3 * (Frame.HEADER_SIZE + 1) && System.nanoTime() < deadline) Thread.sleep(5)
    live.close()
    val reader = FrameReader(ByteArrayInputStream(out.toByteArray()))
    val read = generateSequence { reader.read() }.map { "${it.type} ${it.ptsUs}" }.toList()
    assertEquals(listOf("CONTROL 0", "VIDEO 1", "VIDEO 2"), read)
  }

  @Test
  fun aFailedWriteIsReportedOnce() {
    val failed = CountDownLatch(1)
    var failures = 0
    val live = SendQueue(
      object : SendQueue.Listener {
        override fun onCongested() = Unit

        override fun onWriteFailed(error: IOException) {
          failures++
          failed.countDown()
        }
      },
    )
    live.start(FrameWriter(object : OutputStream() {
      override fun write(b: Int) = throw IOException("Connection reset")
    }))
    live.offer(FrameType.CONTROL, 0, 0, byteArrayOf(1), 1)
    assertTrue(failed.await(2, TimeUnit.SECONDS))
    live.offer(FrameType.CONTROL, 0, 0, byteArrayOf(2), 1)
    Thread.sleep(50)
    assertEquals(1, failures)
    assertNull(live.poll())
  }

  @Test
  fun theSocketBufferHoldsAboutATenthOfASecond() {
    assertEquals(18_750, SendQueue.socketBufferBytes(3_000_000), "doubled by Linux: 100 ms at 3 Mbit/s")
    assertEquals(64 * 1024, SendQueue.socketBufferBytes(12_000_000))
    assertEquals(8 * 1024, SendQueue.socketBufferBytes(1_000_000), "but not so small that round trips run dry")
  }
}
