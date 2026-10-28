package io.github.lumpilabs.beamline.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.lumpilabs.beamline.protocol.AudioConfig
import io.github.lumpilabs.beamline.protocol.AudioLevel
import io.github.lumpilabs.beamline.protocol.Codecs
import io.github.lumpilabs.beamline.protocol.Frame
import io.github.lumpilabs.beamline.protocol.FrameFlags
import io.github.lumpilabs.beamline.protocol.OpusConfig
import io.github.lumpilabs.beamline.protocol.ProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Decodes the sender's Opus audio and plays it. Playback starts once [START_BUFFER_MS] is queued,
 * which absorbs Wi-Fi bursts; when more than [MAX_BUFFERED_MS] piles up (the two devices' clocks
 * drifting apart over a long session), a chunk is dropped to keep the delay bounded.
 *
 * Android's mixer disables a track that runs dry for a while, for example before the phone plays
 * anything. A track whose position stops moving is therefore restarted automatically.
 *
 * [audibleOffsetNs] tells when a sender timestamp is heard, for syncing video to the sound.
 * All codec and track calls happen on one handler thread.
 */
class AudioPlayer(private val config: AudioConfig) {
  private val thread = HandlerThread("beamline-audio").also { it.start() }
  private val handler = Handler(thread.looper)
  private val bytesPerFrame = config.channels * 2
  private val track: AudioTrack
  private var codec: MediaCodec? = null
  private var released = false
  private var playing = false
  private var framesWritten = 0L
  private var headBase = 0L
  private var lastHead = 0L
  private var lastHeadChangeNs = 0L
  private val timestamp = AudioTimestamp()
  private val segments = ArrayDeque<Segment>()

  // Diagnostics, logged every few seconds.
  private var peakDbfs = -96.0
  private var droppedChunks = 0
  private var restarts = 0
  private var lastReportNs = 0L

  /** Local System.nanoTime minus sender time (ns) at which audio is heard, smoothed; null while silent. */
  @Volatile var audibleOffsetNs: Long? = null
    private set

  init {
    if (config.codec != Codecs.OPUS) throw ProtocolException("Unsupported audio codec ${config.codec}")
    val channelMask = if (config.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val minBuffer = AudioTrack.getMinBufferSize(config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    // A normal mixer track: low-latency tracks get disabled much sooner when Wi-Fi delays a packet.
    track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(config.sampleRate)
          .setChannelMask(channelMask)
          .build(),
      )
      .setBufferSizeInBytes(max(minBuffer, framesFor(TRACK_BUFFER_MS) * bytesPerFrame))
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }

  fun submit(frame: Frame) {
    handler.post { handle(frame) }
  }

  fun release() {
    handler.post {
      released = true
      audibleOffsetNs = null
      codec?.let {
        runCatching { it.stop() }
        it.release()
      }
      codec = null
      runCatching { track.stop() }
      track.release()
    }
    thread.quitSafely()
  }

  private fun handle(frame: Frame) {
    if (released) return
    try {
      if (frame.hasFlag(FrameFlags.CODEC_CONFIG)) {
        startDecoder(OpusConfig.parse(frame.body))
        return
      }
      val decoder = codec ?: return
      val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
      if (index < 0) return
      decoder.getInputBuffer(index)?.apply {
        clear()
        put(frame.body)
      }
      decoder.queueInputBuffer(index, 0, frame.body.size, frame.ptsUs, 0)
      drain(decoder)
    } catch (e: Exception) {
      Log.w(TAG, "Audio frame failed", e)
    }
  }

  private fun startDecoder(opus: OpusConfig) {
    codec?.release()
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, config.sampleRate, config.channels).apply {
      setByteBuffer("csd-0", ByteBuffer.wrap(opus.header))
      setByteBuffer("csd-1", nativeLong(opus.codecDelayNs))
      setByteBuffer("csd-2", nativeLong(opus.seekPreRollNs))
    }
    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
      configure(format, null, null, 0)
      start()
    }
  }

  private fun drain(decoder: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    while (true) {
      val index = decoder.dequeueOutputBuffer(info, 0)
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
      if (index < 0) continue
      val pcm = ByteArray(info.size)
      decoder.getOutputBuffer(index)?.let { buffer ->
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(pcm)
      }
      val ptsUs = info.presentationTimeUs
      decoder.releaseOutputBuffer(index, false)
      if (pcm.isNotEmpty()) play(pcm, ptsUs)
    }
  }

  private fun play(pcm: ByteArray, ptsUs: Long) {
    peakDbfs = max(peakDbfs, AudioLevel.peakDbfs(pcm))
    if (playing) restartIfStalled()
    if (playing && bufferedFrames() > framesFor(MAX_BUFFERED_MS)) {
      droppedChunks++
    } else {
      val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
      if (written > 0) {
        segments.addLast(Segment(framesWritten, ptsUs))
        while (segments.size > MAX_SEGMENTS) segments.removeFirst()
        framesWritten += written / bytesPerFrame
      }
      if (!playing && framesWritten >= framesFor(START_BUFFER_MS)) {
        track.play()
        playing = true
        lastHead = headPosition()
        lastHeadChangeNs = System.nanoTime()
      }
    }
    updateClock()
    report()
  }

  /** Android's mixer disables a track that ran dry; a frozen position means it needs a restart. */
  private fun restartIfStalled() {
    val head = headPosition()
    val now = System.nanoTime()
    if (head != lastHead) {
      lastHead = head
      lastHeadChangeNs = now
      return
    }
    if (now - lastHeadChangeNs < STALL_NS) return
    restarts++
    runCatching {
      track.pause()
      track.flush()
    }
    playing = false
    framesWritten = 0
    headBase = headPosition()
    segments.clear()
    audibleOffsetNs = null
  }

  private fun updateClock() {
    if (!playing || !track.getTimestamp(timestamp)) return
    val position = timestamp.framePosition - headBase
    val segment = segments.lastOrNull { it.startFrame <= position } ?: return
    val ptsUs = segment.ptsUs + (position - segment.startFrame) * 1_000_000 / config.sampleRate
    val offset = timestamp.nanoTime - ptsUs * 1000
    audibleOffsetNs = audibleOffsetNs?.let { it + (offset - it) / 8 } ?: offset
  }

  private fun report() {
    val now = System.nanoTime()
    if (now - lastReportNs < REPORT_INTERVAL_NS) return
    lastReportNs = now
    Log.i(
      TAG,
      "Audio peak %.0f dBFS, buffered %d ms, dropped %d chunks, %d restarts".format(
        peakDbfs,
        bufferedFrames() * 1000 / config.sampleRate,
        droppedChunks,
        restarts,
      ),
    )
    peakDbfs = -96.0
  }

  private fun headPosition(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL

  private fun bufferedFrames(): Long = framesWritten - (headPosition() - headBase)

  private fun framesFor(ms: Int): Int = config.sampleRate * ms / 1000

  private fun nativeLong(value: Long): ByteBuffer =
    ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }

  private class Segment(val startFrame: Long, val ptsUs: Long)

  private companion object {
    const val TAG = "BeamlineAudio"
    /** Smooth playback matters more than low delay: about a quarter second of cushion. */
    const val START_BUFFER_MS = 250
    const val MAX_BUFFERED_MS = 500
    const val TRACK_BUFFER_MS = 800
    const val MAX_SEGMENTS = 200
    const val INPUT_TIMEOUT_US = 10_000L
    const val STALL_NS = 300_000_000L
    const val REPORT_INTERVAL_NS = 5_000_000_000L
  }
}
