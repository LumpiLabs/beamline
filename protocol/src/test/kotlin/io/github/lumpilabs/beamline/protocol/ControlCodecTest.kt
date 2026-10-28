package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ControlCodecTest {
  @Test
  fun roundTripsEveryMessage() {
    val messages = listOf(
      Hello(listOf(1), "Pixel 8", listOf(Codecs.H264, Codecs.HEVC), listOf(Codecs.OPUS)),
      Welcome(1, "Living room TV", 1920, 1080, 60),
      StreamConfig(Codecs.H264, 1080, 2400, 60),
      BitrateHint(8_000_000),
      Ping(7, 123_456_789),
      Pong(7, 123_456_789, 987_654_321),
      Stats(59.9f, 7_900_000, 1, 0, 2),
      Bye("user stopped"),
    )
    for (message in messages) {
      assertEquals(message, ControlCodec.decode(ControlCodec.encode(message)))
    }
  }

  @Test
  fun roundTripsObjectMessages() {
    assertSame(RequestKeyframe, ControlCodec.decode(ControlCodec.encode(RequestKeyframe)))
  }

  @Test
  fun wrapsInAControlFrame() {
    val frame = ControlCodec.toFrame(BitrateHint(1))
    assertEquals(FrameType.CONTROL, frame.type)
    assertEquals(0, frame.flags)
    assertEquals(0, frame.ptsUs)
    assertEquals(BitrateHint(1), ControlCodec.decode(frame.body))
  }

  @Test
  fun rejectsGarbage() {
    assertFailsWith<ProtocolException> { ControlCodec.decode(byteArrayOf(0x42, 0x13, 0x37)) }
  }
}
