package io.github.lumpilabs.beamline.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ControlCodecTest {
  @Test
  fun roundTripsEveryMessage() {
    val messages = listOf(
      Hello(listOf(1), "Pixel 8", listOf(Codecs.H264, Codecs.HEVC), listOf(Codecs.OPUS)),
      Hello(listOf(1), "Pixel 8", listOf(Codecs.H264), listOf(Codecs.OPUS), "6f1c2a"),
      Welcome(1, "Living room TV", 1920, 1080, 60),
      Welcome(1, "Living room TV", 1920, 1080, 60, listOf(Codecs.OPUS)),
      AudioConfig(Codecs.OPUS, 48_000, 2),
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
  fun encodesAsTypeNameAndBody() {
    // The wire format in docs/DESIGN.md: a CBOR array of the message name and a map.
    val bytes = ControlCodec.encode(BitrateHint(1))
    assertEquals(0x82, bytes[0].toInt() and 0xFF)
    assertEquals("bitrate_hint", bytes.copyOfRange(2, 2 + 12).decodeToString())
  }

  @Test
  fun welcomeFromAReceiverWithoutSoundHasNoAudioCodecs() {
    // Receivers before sound support send welcome without the audio_codecs field.
    @OptIn(ExperimentalSerializationApi::class)
    val body = Cbor.encodeToByteArray(LegacyWelcome.serializer(), LegacyWelcome(1, "Old TV", 1920, 1080, 60))
    val name = "welcome".encodeToByteArray()
    val legacy = byteArrayOf(0x82.toByte(), (0x60 + name.size).toByte()) + name + body
    assertEquals(Welcome(1, "Old TV", 1920, 1080, 60), ControlCodec.decode(legacy))
  }

  @Serializable
  private data class LegacyWelcome(
    val version: Int,
    val name: String,
    @SerialName("max_width") val maxWidth: Int,
    @SerialName("max_height") val maxHeight: Int,
    @SerialName("max_fps") val maxFps: Int,
  )

  @Test
  fun helloWithoutSenderIdLeavesTheFieldOut() {
    val bytes = ControlCodec.encode(Hello(listOf(1), "Pixel 8", listOf(Codecs.H264), emptyList()))
    assertFalse("sender_id" in bytes.decodeToString())
  }

  @Test
  fun rejectsGarbage() {
    assertFailsWith<ProtocolException> { ControlCodec.decode(byteArrayOf(0x42, 0x13, 0x37)) }
  }
}
