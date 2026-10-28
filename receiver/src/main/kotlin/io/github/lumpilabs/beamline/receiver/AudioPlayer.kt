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
import kotlin.math.min

/**
 * Decodes the sender's Opus audio and plays it. Playback starts once [START_BUFFER_MS] is queued,
 * which absorbs Wi-Fi bursts; when more than [MAX_BUFFERED_MS] piles up, a chunk is dropped to
 * keep the delay bounded.
 *
 * A burst can also leave the queue high for good: after a reconnect, or after the TV's audio
 * output paused for a refresh-rate switch, it can sit near [MAX_BUFFERED_MS], where the slightest
 * bunching of packets drops chunks. When even its lowest level stays [TRIM_MARGIN_MS] above the
 * start cushion for [TRIM_WINDOW_NS], the excess is skipped once, in one piece. The same keeps the
 * two devices' clocks drifting apart over a long session from adding delay.
 *
 * Before Android 12, a started or flushed track only plays once its whole buffer is full. While
 * filling, the track's usable size is therefore shrunk to the start cushion, and grown back once
 * the mixer takes the track so bursts still fit. Decoded audio that doesn't fit yet waits in
 * [pending].
 *
 * Android's mixer disables a track that runs dry for a while, for example before the phone plays
 * anything. A track whose position stops moving is therefore restarted automatically.
 *
 * [audibleOffsetNs] tells when a sender timestamp is heard, for syncing video to the sound. The
 * queue level and every gap are counted in [stats]. All codec and track calls happen on one
 * handler thread.
 */
class AudioPlayer(private val config: AudioConfig, private val stats: PlaybackStats) {
  private val thread = HandlerThread("beamline-audio").also { it.start() }
  private val handler = Handler(thread.looper)
  private val bytesPerFrame = config.channels * 2
  private val track: AudioTrack
  private var codec: MediaCodec? = null
  private var released = false
  private var playing = false
  private var startFrames = 0
  private var expanded = false
  private val pending = ArrayDeque<Pcm>()
  private var pendingFrames = 0L
  private var framesWritten = 0L
  private var headBase = 0L
  private var lastHead = 0L
  private var lastHeadChangeNs = 0L
  private val timestamp = AudioTimestamp()
  private val segments = ArrayDeque<Segment>()

  /** Lowest queue level since [floorSinceNs], and how much incoming audio is still to be skipped. */
  private var floorFrames = Long.MAX_VALUE
  private var floorSinceNs = 0L
  private var trimFrames = 0L

  // Logged every few seconds, with the totals from [stats].
  private var peakDbfs = -96.0
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
    shrinkForStart()
  }

  fun submit(frame: Frame) {
    handler.post { handle(frame) }
  }

  fun release() {
    handler.post {
      released = true
      audibleOffsetNs = null
      stats.soundQueuedMs = null
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
    val frames = pcm.size / bytesPerFrame
    val queued = bufferedFrames() + pendingFrames
    stats.soundQueuedMs = queued * 1000 / config.sampleRate
    if (trimFrames > 0) {
      trimFrames -= frames
    } else if (queued > framesFor(MAX_BUFFERED_MS)) {
      stats.soundDroppedChunks.incrementAndGet()
    } else {
      pending.addLast(Pcm(pcm, ptsUs))
      pendingFrames += frames
    }
    writePending()
    if (!playing && framesWritten >= startFrames) {
      track.play()
      playing = true
      lastHead = headPosition()
      lastHeadChangeNs = System.nanoTime()
    }
    if (expanded) trimIfBacklogged(queued)
    updateClock()
    report()
  }

  /** Skips the excess when the queue's lowest level stayed well above the start cushion. */
  private fun trimIfBacklogged(queued: Long) {
    floorFrames = min(floorFrames, queued)
    val now = System.nanoTime()
    if (now - floorSinceNs < TRIM_WINDOW_NS) return
    if (floorFrames > framesFor(START_BUFFER_MS + TRIM_MARGIN_MS)) {
      trimFrames = floorFrames - framesFor(START_BUFFER_MS)
      val ms = trimFrames * 1000 / config.sampleRate
      stats.soundSkippedMs.addAndGet(ms)
      Log.i(TAG, "Skipping $ms ms of sound to bring the delay back down")
    }
    floorFrames = Long.MAX_VALUE
    floorSinceNs = now
  }

  /** Moves decoded audio into the track as far as it has room. */
  private fun writePending() {
    while (true) {
      val chunk = pending.firstOrNull() ?: return
      val written = track.write(chunk.data, chunk.offset, chunk.data.size - chunk.offset, AudioTrack.WRITE_NON_BLOCKING)
      if (written <= 0) return
      val offsetUs = (chunk.offset / bytesPerFrame).toLong() * 1_000_000 / config.sampleRate
      segments.addLast(Segment(framesWritten, chunk.ptsUs + offsetUs))
      while (segments.size > MAX_SEGMENTS) segments.removeFirst()
      framesWritten += written / bytesPerFrame
      pendingFrames -= written / bytesPerFrame
      chunk.offset += written
      if (chunk.offset < chunk.data.size) return
      pending.removeFirst()
    }
  }

  /** Lets the track start as soon as the start cushion is queued instead of its whole buffer. */
  private fun shrinkForStart() {
    track.setBufferSizeInFrames(framesFor(START_BUFFER_MS))
    startFrames = track.bufferSizeInFrames
    expanded = false
  }

  /** Android's mixer disables a track that ran dry; a frozen position means it needs a restart. */
  private fun restartIfStalled() {
    val head = headPosition()
    val now = System.nanoTime()
    if (head != lastHead) {
      lastHead = head
      lastHeadChangeNs = now
      // The mixer took the track: give bursts the whole buffer again, and start watching the
      // queue level from here, not from the filling before.
      if (!expanded) {
        track.setBufferSizeInFrames(track.bufferCapacityInFrames)
        expanded = true
        floorFrames = Long.MAX_VALUE
        floorSinceNs = now
      }
      return
    }
    if (now - lastHeadChangeNs < STALL_NS) return
    stats.soundRestarts.incrementAndGet()
    runCatching {
      track.pause()
      track.flush()
    }
    playing = false
    trimFrames = 0
    framesWritten = 0
    headBase = headPosition()
    segments.clear()
    audibleOffsetNs = null
    shrinkForStart()
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
      "Audio peak %.0f dBFS, buffered %d ms, dropped %d chunks, skipped %d ms, %d restarts".format(
        peakDbfs,
        bufferedFrames() * 1000 / config.sampleRate,
        stats.soundDroppedChunks.get(),
        stats.soundSkippedMs.get(),
        stats.soundRestarts.get(),
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

  private class Pcm(val data: ByteArray, val ptsUs: Long) {
    var offset = 0
  }

  private companion object {
    const val TAG = "BeamlineAudio"
    /** Smooth playback matters more than low delay: about a third of a second of cushion. */
    const val START_BUFFER_MS = 300
    const val MAX_BUFFERED_MS = 600

    /** Normal Wi-Fi jitter keeps dipping the queue well below this much above the cushion. */
    const val TRIM_MARGIN_MS = 100
    const val TRIM_WINDOW_NS = 2_000_000_000L
    const val TRACK_BUFFER_MS = 1000
    const val MAX_SEGMENTS = 200
    const val INPUT_TIMEOUT_US = 10_000L
    const val STALL_NS = 300_000_000L
    const val REPORT_INTERVAL_NS = 5_000_000_000L
  }
}
