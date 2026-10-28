package io.github.lumpilabs.beamline.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpusConfigTest {
  /** OpusHead for stereo, 48 kHz, pre-skip 312 samples. */
  private val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("OpusHead".encodeToByteArray())
    put(1) // version
    put(2) // channels
    putShort(312) // pre-skip
    putInt(48_000)
    putShort(0) // output gain
    put(0) // channel mapping family
  }.array()

  private fun block(tag: String, payload: ByteArray): ByteArray =
    ByteBuffer.allocate(16 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
      .put(tag.encodeToByteArray()).putLong(payload.size.toLong()).put(payload).array()

  private fun longLe(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

  @Test
  fun parsesAndroidTaggedConfig() {
    val config = ByteArrayOutputStream().apply {
      write(block("AOPUSHDR", head))
      write(block("AOPUSDLY", longLe(6_500_000)))
      write(block("AOPUSPRL", longLe(80_000_000)))
    }.toByteArray()
    val parsed = OpusConfig.parse(config)
    assertContentEquals(head, parsed.header)
    assertEquals(6_500_000, parsed.codecDelayNs)
    assertEquals(80_000_000, parsed.seekPreRollNs)
  }

  @Test
  fun derivesDelayFromPreSkipWhenMissing() {
    val parsed = OpusConfig.parse(block("AOPUSHDR", head))
    assertEquals(312 * 1_000_000_000L / 48_000, parsed.codecDelayNs)
    assertEquals(80_000_000, parsed.seekPreRollNs)
  }

  @Test
  fun acceptsPlainOpusHead() {
    val parsed = OpusConfig.parse(head)
    assertContentEquals(head, parsed.header)
    assertEquals(6_500_000, parsed.codecDelayNs)
  }

  @Test
  fun rejectsOtherData() {
    assertFailsWith<ProtocolException> { OpusConfig.parse(byteArrayOf(1, 2, 3)) }
    assertFailsWith<ProtocolException> { OpusConfig.parse(block("AOPUSDLY", longLe(1))) }
  }
}
