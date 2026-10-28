package io.github.lumpilabs.beamline.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameTest {
  @Test
  fun roundTripsSeveralFrames() {
    val frames = listOf(
      Frame(FrameType.VIDEO, FrameFlags.CODEC_CONFIG, 0, byteArrayOf(0, 0, 0, 1, 0x67)),
      Frame(FrameType.VIDEO, FrameFlags.KEYFRAME, 16_683, ByteArray(70_000) { it.toByte() }),
      Frame(FrameType.AUDIO, 0, 20_000, byteArrayOf(1, 2, 3)),
      Frame(FrameType.CONTROL, 0, 0, ByteArray(0)),
    )
    val bytes = ByteArrayOutputStream()
    val writer = FrameWriter(bytes)
    frames.forEach(writer::write)

    val reader = FrameReader(ByteArrayInputStream(bytes.toByteArray()))
    for (expected in frames) {
      val actual = reader.read()!!
      assertEquals(expected.type, actual.type)
      assertEquals(expected.flags, actual.flags)
      assertEquals(expected.ptsUs, actual.ptsUs)
      assertContentEquals(expected.body, actual.body)
    }
    assertNull(reader.read())
  }

  @Test
  fun headerLayoutMatchesTheSpec() {
    val bytes = ByteArrayOutputStream()
    FrameWriter(bytes).write(Frame(FrameType.AUDIO, FrameFlags.CODEC_CONFIG, 0x0102030405060708, byteArrayOf(9)))
    val expected = byteArrayOf(
      0x03, 0x02, // type, flags
      0, 0, 0, 1, // length
      1, 2, 3, 4, 5, 6, 7, 8, // pts_us
      9, // body
    )
    assertContentEquals(expected, bytes.toByteArray())
    assertEquals(Frame.HEADER_SIZE, expected.size - 1)
  }

  @Test
  fun flagsAreReadable() {
    val frame = Frame(FrameType.VIDEO, FrameFlags.KEYFRAME or FrameFlags.CODEC_CONFIG, 0, ByteArray(0))
    assertTrue(frame.hasFlag(FrameFlags.KEYFRAME))
    assertTrue(frame.hasFlag(FrameFlags.CODEC_CONFIG))
  }

  @Test
  fun rejectsUnknownFrameType() {
    val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0x7f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    assertFailsWith<ProtocolException> { reader.read() }
  }

  @Test
  fun rejectsOversizedBody() {
    val header = byteArrayOf(0x02, 0, 0x7f, 0x7f, 0x7f, 0x7f, 0, 0, 0, 0, 0, 0, 0, 0)
    assertFailsWith<ProtocolException> { FrameReader(ByteArrayInputStream(header)).read() }
  }

  @Test
  fun rejectsStreamThatEndsInsideAFrame() {
    val bytes = ByteArrayOutputStream()
    FrameWriter(bytes).write(Frame(FrameType.VIDEO, 0, 1, ByteArray(100)))
    val truncated = bytes.toByteArray().copyOf(50)
    assertFailsWith<ProtocolException> { FrameReader(ByteArrayInputStream(truncated)).read() }
  }
}
