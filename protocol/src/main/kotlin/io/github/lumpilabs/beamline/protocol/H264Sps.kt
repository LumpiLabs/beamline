package io.github.lumpilabs.beamline.protocol

import java.io.ByteArrayOutputStream

/**
 * Rewrites H.264 sequence parameter sets (SPS) to say that pictures come out of the decoder in the
 * order they go in.
 *
 * Phone encoders make no B-frames, so every picture could be shown as soon as it is decoded. Their
 * SPS doesn't say so, though: it lacks the bitstream restriction in its video usability
 * information (VUI). Some decoders then keep pictures back in case a later one has to be shown
 * first. The Fire TV Stick 4K Max holds each one until the next arrives, even in its low-latency
 * mode, which costs a frame's time and keeps the last change of a still screen from showing at all.
 * With max_num_reorder_frames 0 and max_dec_frame_buffering as small as the reference pictures
 * allow, it hands each picture out at once.
 */
object H264Sps {
  /**
   * [annexB] with every SPS rewritten, or [annexB] itself when nothing changes: most frames have
   * no SPS, and some encoders (the Galaxy S26+'s) already say what's needed. Parameter sets come
   * before the first slice, so the search stops there. An SPS that can't be parsed is kept.
   */
  fun withoutReordering(annexB: ByteArray): ByteArray {
    var out: ByteArrayOutputStream? = null
    var copiedUpTo = 0
    var start = payloadAfterStartCode(annexB, 0)
    while (start in annexB.indices) {
      val type = annexB[start].toInt() and 0x1F
      if (type in FIRST_SLICE_TYPE..LAST_SLICE_TYPE) break
      val next = payloadAfterStartCode(annexB, start)
      var end = if (next < 0) annexB.size else next - START_CODE_SIZE
      while (end > start && annexB[end - 1].toInt() == 0) end--
      if (type == SPS_TYPE) {
        val original = annexB.copyOfRange(start, end)
        rewrite(original)?.takeUnless { it.contentEquals(original) }?.let { sps ->
          val buffer = out ?: ByteArrayOutputStream(annexB.size + 16).also { out = it }
          buffer.write(annexB, copiedUpTo, start - copiedUpTo)
          buffer.write(sps)
          copiedUpTo = end
        }
      }
      start = next
    }
    val buffer = out ?: return annexB
    buffer.write(annexB, copiedUpTo, annexB.size - copiedUpTo)
    return buffer.toByteArray()
  }

  /** The fields that matter here, from one SPS NAL unit (with its header, without start code). */
  internal fun parse(nal: ByteArray): Fields = copy(nal, BitWriter())

  internal data class Fields(
    val profile: Int,
    val maxNumRefFrames: Long,
    val widthInMbs: Long,
    val heightInMapUnits: Long,
    val maxNumReorderFrames: Long?,
    val maxDecFrameBuffering: Long?,
  )

  private fun rewrite(nal: ByteArray): ByteArray? =
    try {
      val writer = BitWriter()
      copy(nal, writer, rewrite = true)
      byteArrayOf(nal[0]) + escape(writer.toByteArray())
    } catch (_: RuntimeException) {
      null
    }

  /**
   * Reads the SPS in [nal], copying it bit for bit to [writer]. With [rewrite], the bitstream
   * restriction is replaced or added, and the RBSP trailing bits end the copy.
   */
  private fun copy(nal: ByteArray, writer: BitWriter, rewrite: Boolean = false): Fields {
    val r = BitReader(unescape(nal, 1))
    val w = writer
    fun u(n: Int): Long = r.bits(n).also { w.bits(it, n) }
    fun flag(): Boolean = u(1) == 1L
    fun ue(): Long = r.ue().also { w.ue(it) }
    fun se() {
      w.se(r.se())
    }

    val profile = u(8).toInt()
    u(8) // constraint flags
    u(8) // level
    ue() // seq_parameter_set_id
    if (profile in PROFILES_WITH_CHROMA_INFO) {
      val chromaFormat = ue()
      if (chromaFormat == 3L) u(1)
      ue() // bit_depth_luma_minus8
      ue() // bit_depth_chroma_minus8
      u(1) // qpprime_y_zero_transform_bypass_flag
      if (flag()) {
        repeat(if (chromaFormat != 3L) 8 else 12) { i ->
          if (flag()) scalingList(if (i < 6) 16 else 64, r, w)
        }
      }
    }
    ue() // log2_max_frame_num_minus4
    when (ue()) {
      0L -> ue() // log2_max_pic_order_cnt_lsb_minus4
      1L -> {
        u(1)
        se()
        se()
        repeat(ue().toInt()) { se() }
      }
    }
    val refFrames = ue()
    u(1) // gaps_in_frame_num_value_allowed_flag
    val width = ue()
    val height = ue()
    if (!flag()) u(1) // frame_mbs_only_flag, else mb_adaptive_frame_field_flag
    u(1) // direct_8x8_inference_flag
    if (flag()) repeat(4) { ue() } // frame cropping

    val hasVui = r.bits(1) == 1L
    var reorder: Long? = null
    var buffering: Long? = null
    if (rewrite) w.bits(1, 1) else w.bits(if (hasVui) 1 else 0, 1)
    if (hasVui) {
      if (flag() && u(8) == EXTENDED_SAR) {
        u(16)
        u(16)
      }
      if (flag()) u(1) // overscan
      if (flag()) {
        u(3)
        u(1)
        if (flag()) u(24) // colour description
      }
      if (flag()) {
        ue()
        ue()
      }
      if (flag()) {
        u(32) // num_units_in_tick
        u(32) // time_scale
        u(1) // fixed_frame_rate_flag
      }
      val nalHrd = flag()
      if (nalHrd) hrd(::u, ::ue)
      val vclHrd = flag()
      if (vclHrd) hrd(::u, ::ue)
      if (nalHrd || vclHrd) u(1) // low_delay_hrd_flag
      u(1) // pic_struct_present_flag
    } else if (rewrite) {
      // A VUI with nothing but the bitstream restriction.
      w.bits(0, VUI_FLAGS_BEFORE_RESTRICTION)
    }
    val hasRestriction = hasVui && r.bits(1) == 1L
    if (hasRestriction) {
      w.bits(1, 1)
      u(1) // motion_vectors_over_pic_boundaries_flag
      repeat(4) { ue() } // max_bytes_per_pic_denom, max_bits_per_mb_denom, log2 max mv lengths
      reorder = r.ue()
      buffering = r.ue()
    } else if (rewrite) {
      w.bits(1, 1)
      w.bits(1, 1)
      w.ue(DEFAULT_MAX_BYTES_PER_PIC_DENOM)
      w.ue(DEFAULT_MAX_BITS_PER_MB_DENOM)
      w.ue(MAX_LOG2_MV_LENGTH)
      w.ue(MAX_LOG2_MV_LENGTH)
    } else if (hasVui) {
      w.bits(0, 1)
    }
    if (rewrite) {
      w.ue(0)
      w.ue(maxOf(1L, refFrames))
      w.trailingBits()
    } else if (hasRestriction) {
      w.ue(reorder!!)
      w.ue(buffering!!)
    }
    return Fields(profile, refFrames, width + 1, height + 1, reorder, buffering)
  }

  private fun scalingList(size: Int, r: BitReader, w: BitWriter) {
    var last = 8L
    var next = 8L
    repeat(size) {
      if (next != 0L) {
        val delta = r.se()
        w.se(delta)
        next = (last + delta + 256) % 256
      }
      if (next != 0L) last = next
    }
  }

  private fun hrd(u: (Int) -> Long, ue: () -> Long) {
    val cpbCount = ue() + 1
    u(4) // bit_rate_scale
    u(4) // cpb_size_scale
    repeat(cpbCount.toInt()) {
      ue() // bit_rate_value_minus1
      ue() // cpb_size_value_minus1
      u(1) // cbr_flag
    }
    u(20) // four delay and offset lengths, 5 bits each
  }

  /** Index just past the next 00 00 01 at or after [from], or -1. */
  private fun payloadAfterStartCode(data: ByteArray, from: Int): Int {
    var i = from
    while (i + 2 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i + START_CODE_SIZE
      i++
    }
    return -1
  }

  /** The RBSP of [nal] from [from] on, without emulation prevention bytes. */
  private fun unescape(nal: ByteArray, from: Int): ByteArray {
    val out = ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (i in from until nal.size) {
      val b = nal[i].toInt() and 0xFF
      if (zeros >= 2 && b == 3) {
        zeros = 0
        continue
      }
      out.write(b)
      zeros = if (b == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  /** [rbsp] with emulation prevention bytes, so it can't contain a start code. */
  private fun escape(rbsp: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(rbsp.size + 4)
    var zeros = 0
    for (byte in rbsp) {
      val b = byte.toInt() and 0xFF
      if (zeros >= 2 && b <= 3) {
        out.write(3)
        zeros = 0
      }
      out.write(b)
      zeros = if (b == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  private class BitReader(private val data: ByteArray) {
    private var position = 0

    fun bits(count: Int): Long {
      var value = 0L
      repeat(count) {
        if (position >= data.size * 8) throw IllegalArgumentException("SPS ends early")
        val bit = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
        value = (value shl 1) or bit.toLong()
        position++
      }
      return value
    }

    /** Exp-Golomb, unsigned. */
    fun ue(): Long {
      var zeros = 0
      while (bits(1) == 0L) {
        if (++zeros > MAX_EXP_GOLOMB_ZEROS) throw IllegalArgumentException("Bad Exp-Golomb code")
      }
      return (1L shl zeros) - 1 + bits(zeros)
    }

    /** Exp-Golomb, signed. */
    fun se(): Long {
      val k = ue()
      return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
    }
  }

  private class BitWriter {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var filled = 0

    fun bits(value: Long, count: Int) {
      for (i in count - 1 downTo 0) {
        current = (current shl 1) or ((value ushr i) and 1L).toInt()
        if (++filled == 8) {
          out.write(current)
          current = 0
          filled = 0
        }
      }
    }

    fun ue(value: Long) {
      val coded = value + 1
      val length = 64 - java.lang.Long.numberOfLeadingZeros(coded)
      bits(0, length - 1)
      bits(coded, length)
    }

    fun se(value: Long) = ue(if (value > 0) value * 2 - 1 else -value * 2)

    /** The stop bit, then zeros up to the next byte. */
    fun trailingBits() {
      bits(1, 1)
      if (filled > 0) bits(0, 8 - filled)
    }

    fun toByteArray(): ByteArray {
      check(filled == 0) { "Not byte aligned" }
      return out.toByteArray()
    }
  }

  private const val SPS_TYPE = 7
  private const val FIRST_SLICE_TYPE = 1
  private const val LAST_SLICE_TYPE = 5
  private const val START_CODE_SIZE = 3
  private const val EXTENDED_SAR = 255L
  private const val MAX_EXP_GOLOMB_ZEROS = 32

  /** Aspect ratio, overscan, video signal, chroma location, timing, NAL HRD, VCL HRD, pic struct. */
  private const val VUI_FLAGS_BEFORE_RESTRICTION = 8

  /** What a decoder assumes when the restriction is missing. */
  private const val DEFAULT_MAX_BYTES_PER_PIC_DENOM = 2L
  private const val DEFAULT_MAX_BITS_PER_MB_DENOM = 1L
  private const val MAX_LOG2_MV_LENGTH = 16L

  /** High profiles and their relatives, whose SPS has chroma format, bit depths and scaling lists. */
  private val PROFILES_WITH_CHROMA_INFO = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
