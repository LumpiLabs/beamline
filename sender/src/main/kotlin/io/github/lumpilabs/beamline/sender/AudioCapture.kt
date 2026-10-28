package io.github.lumpilabs.beamline.sender

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.lumpilabs.beamline.protocol.AudioLevel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Captures what other apps play (media, games and unknown usages) through the screen-capture
 * token and encodes it as Opus in 20 ms frames. Apps that opt out of capture stay silent.
 * Timestamps use the same clock as the video encoder (System.nanoTime), so the TV can line them up.
 */
class AudioCapture @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
  projection: MediaProjection,
  private val sink: Sink,
) {
  interface Sink {
    fun onAudioConfig(data: ByteArray)

    fun onAudioFrame(data: ByteArray, ptsUs: Long)

    fun onError(error: Exception)
  }

  private val record: AudioRecord
  private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
  private val running = AtomicBoolean(true)
  private val worker: Thread

  init {
    val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
      .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
      .addMatchingUsage(AudioAttributes.USAGE_GAME)
      .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
      .build()
    val pcm = AudioFormat.Builder()
      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .setSampleRate(SAMPLE_RATE)
      .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
      .build()
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    record = AudioRecord.Builder()
      .setAudioFormat(pcm)
      .setBufferSizeInBytes(max(minBuffer, CHUNK_BYTES * 4))
      .setAudioPlaybackCaptureConfig(capture)
      .build()

    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS).apply {
      setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
    }
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    record.startRecording()
    worker = thread(name = "beamline-audio", isDaemon = true) { loop() }
  }

  fun stop() {
    if (!running.getAndSet(false)) return
    if (Thread.currentThread() != worker) worker.join(500)
    runCatching { record.stop() }
    record.release()
    runCatching { codec.stop() }
    codec.release()
  }

  private fun loop() {
    val pcm = ByteArray(CHUNK_BYTES)
    val info = MediaCodec.BufferInfo()
    try {
      while (running.get()) {
        var read = 0
        while (read < CHUNK_BYTES && running.get()) {
          val n = record.read(pcm, read, CHUNK_BYTES - read)
          if (n < 0) throw IOException("Audio capture failed ($n)")
          read += n
        }
        if (!running.get()) break
        reportLevel(pcm, read)
        // The chunk ends now, so its first sample was captured one chunk earlier.
        val ptsUs = System.nanoTime() / 1000 - CHUNK_US
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
          codec.getInputBuffer(index)?.apply {
            clear()
            put(pcm, 0, read)
          }
          codec.queueInputBuffer(index, 0, read, ptsUs, 0)
        }
        drain(info)
      }
    } catch (e: Exception) {
      if (running.get()) sink.onError(e)
    }
  }

  private var peakDbfs = -96.0
  private var lastReportNs = 0L

  /** Logs the loudest captured sample every few seconds: -96 dBFS means the capture is silent. */
  private fun reportLevel(pcm: ByteArray, length: Int) {
    peakDbfs = max(peakDbfs, AudioLevel.peakDbfs(pcm, length))
    val now = System.nanoTime()
    if (now - lastReportNs < REPORT_INTERVAL_NS) return
    lastReportNs = now
    Log.i(TAG, "Captured audio peak %.0f dBFS".format(peakDbfs))
    peakDbfs = -96.0
  }

  private fun drain(info: MediaCodec.BufferInfo) {
    while (true) {
      val index = codec.dequeueOutputBuffer(info, 0)
      if (index < 0) return
      val data = ByteArray(info.size)
      codec.getOutputBuffer(index)?.let { buffer ->
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(data)
      }
      val flags = info.flags
      val ptsUs = info.presentationTimeUs
      codec.releaseOutputBuffer(index, false)
      if (data.isEmpty()) continue
      if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) sink.onAudioConfig(data) else sink.onAudioFrame(data, ptsUs)
    }
  }

  companion object {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    private const val BITRATE = 128_000

    /** One Opus frame: 20 ms of 16-bit stereo. */
    private const val CHUNK_US = 20_000L
    private const val CHUNK_BYTES = SAMPLE_RATE / 50 * CHANNELS * 2
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val REPORT_INTERVAL_NS = 5_000_000_000L
    private const val TAG = "BeamlineAudio"
  }
}
