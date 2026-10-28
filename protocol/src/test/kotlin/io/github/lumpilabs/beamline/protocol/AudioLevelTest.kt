package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioLevelTest {
  private fun pcm(vararg samples: Int): ByteArray =
    samples.flatMap { listOf((it and 0xFF).toByte(), ((it shr 8) and 0xFF).toByte()) }.toByteArray()

  @Test
  fun silenceIsMinus96() {
    assertEquals(-96.0, AudioLevel.peakDbfs(ByteArray(960)))
  }

  @Test
  fun fullScaleIsAboutZero() {
    assertEquals(0.0, AudioLevel.peakDbfs(pcm(0, -32768, 100)), 0.01)
  }

  @Test
  fun halfScaleIsMinusSix() {
    assertEquals(-6.02, AudioLevel.peakDbfs(pcm(16384, -1200)), 0.01)
  }
}
