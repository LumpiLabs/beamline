package io.github.lumpilabs.beamline.windows

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
  private val directory = Files.createTempDirectory("beamline-settings").toFile().apply { deleteOnExit() }

  @Test
  fun everythingIsKeptForTheNextRun() {
    val first = Settings(directory)
    val senderId = first.senderId
    first.rememberPaired("abc")
    first.rememberPaired("def")
    first.lowLatency = true
    first.screen = 1
    first.lastTv = TvAddress("10.0.0.5", 47800, "Living room")

    val next = Settings(directory)
    assertEquals(senderId, next.senderId)
    assertTrue(next.isPaired("abc"))
    assertTrue(next.isPaired("def"))
    assertFalse(next.isPaired("xyz"))
    assertTrue(next.lowLatency)
    assertEquals(1, next.screen)
    assertEquals(TvAddress("10.0.0.5", 47800, "Living room"), next.lastTv)
  }

  @Test
  fun aNewInstallHasDefaults() {
    val settings = Settings(directory.resolve("new"))
    assertFalse(settings.lowLatency)
    assertEquals(0, settings.screen)
    assertEquals(null, settings.lastTv)
  }
}
