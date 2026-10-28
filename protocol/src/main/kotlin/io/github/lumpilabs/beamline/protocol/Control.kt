package io.github.lumpilabs.beamline.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor

/**
 * Control messages, carried CBOR-encoded in [FrameType.CONTROL] frames as
 * `{"type": "<serial name>", "value": {...}}`.
 */
@Serializable
sealed interface Control

/** Sender to receiver, first message of a session. */
@Serializable
@SerialName("hello")
data class Hello(
  val versions: List<Int>,
  val name: String,
  @SerialName("video_codecs") val videoCodecs: List<String>,
  @SerialName("audio_codecs") val audioCodecs: List<String>,
) : Control

/** Receiver to sender, answer to [Hello] with the chosen version and stream limits. */
@Serializable
@SerialName("welcome")
data class Welcome(
  val version: Int,
  val name: String,
  @SerialName("max_width") val maxWidth: Int,
  @SerialName("max_height") val maxHeight: Int,
  @SerialName("max_fps") val maxFps: Int,
) : Control

/** Sender to receiver, before the codec config of every new video stream (start, rotation). */
@Serializable
@SerialName("stream")
data class StreamConfig(
  val codec: String,
  val width: Int,
  val height: Int,
  val fps: Int,
) : Control

/** Receiver to sender: the decoder lost sync and needs a keyframe. */
@Serializable
@SerialName("request_keyframe")
data object RequestKeyframe : Control

/** Receiver to sender: suggested video bitrate. */
@Serializable
@SerialName("bitrate_hint")
data class BitrateHint(val bps: Int) : Control

/** Either direction; the peer answers with [Pong] to measure round-trip time and clock offset. */
@Serializable
@SerialName("ping")
data class Ping(val id: Int, @SerialName("time_us") val timeUs: Long) : Control

@Serializable
@SerialName("pong")
data class Pong(
  val id: Int,
  @SerialName("ping_time_us") val pingTimeUs: Long,
  @SerialName("time_us") val timeUs: Long,
) : Control

/** Receiver to sender, about once a second. */
@Serializable
@SerialName("stats")
data class Stats(
  val fps: Float,
  @SerialName("bitrate_bps") val bitrateBps: Int,
  @SerialName("decode_queue") val decodeQueue: Int,
  @SerialName("late_frames") val lateFrames: Int,
  @SerialName("dropped_frames") val droppedFrames: Int,
) : Control

/** Either direction, last message before closing the connection. */
@Serializable
@SerialName("bye")
data class Bye(val reason: String) : Control

@OptIn(ExperimentalSerializationApi::class)
object ControlCodec {
  private val cbor = Cbor { ignoreUnknownKeys = true }

  fun encode(message: Control): ByteArray = cbor.encodeToByteArray(Control.serializer(), message)

  fun decode(bytes: ByteArray): Control =
    try {
      cbor.decodeFromByteArray(Control.serializer(), bytes)
    } catch (e: SerializationException) {
      throw ProtocolException("Malformed control message: ${e.message}")
    } catch (e: IllegalArgumentException) {
      throw ProtocolException("Malformed control message: ${e.message}")
    }

  fun toFrame(message: Control): Frame = Frame(FrameType.CONTROL, 0, 0, encode(message))
}
