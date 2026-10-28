package io.github.lumpilabs.beamline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamSizingTest {
  private val tv = VideoSize(1920, 1080)

  @Test
  fun portraitPhoneIsLimitedByTheTvHeight() {
    assertEquals(VideoSize(480, 1072), StreamSizing.fit(VideoSize(1080, 2400), tv))
  }

  @Test
  fun landscapePhoneIsLimitedByTheTvWidth() {
    assertEquals(VideoSize(1920, 864), StreamSizing.fit(VideoSize(2400, 1080), tv))
  }

  @Test
  fun smallScreensAreNotScaledUp() {
    assertEquals(VideoSize(1280, 720), StreamSizing.fit(VideoSize(1280, 720), tv))
  }

  @Test
  fun alignsToSixteen() {
    val size = StreamSizing.fit(VideoSize(1081, 2341), tv)
    assertEquals(0, size.width % 16)
    assertEquals(0, size.height % 16)
  }

  @Test
  fun negotiatesTheHighestCommonVersion() {
    assertEquals(1, Protocol.negotiate(listOf(1)))
    assertEquals(1, Protocol.negotiate(listOf(3, 1, 2)))
    assertNull(Protocol.negotiate(listOf(2, 3)))
    assertNull(Protocol.negotiate(emptyList()))
  }
}
