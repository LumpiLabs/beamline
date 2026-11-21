package io.github.lumpilabs.beamline.windows

import java.io.File
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ResourceBundle

/** The app's words in English or German, after Windows' display language. */
object Text {
  private val bundle = ResourceBundle.getBundle("io.github.lumpilabs.beamline.windows.strings")

  operator fun get(key: String, vararg args: Any?): String = bundle.getString(key).format(*args)
}

/** A log on the console and in %APPDATA%\Beamline\beamline.log, started afresh on every run. */
object Log {
  private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
  private val file: PrintStream? =
    runCatching {
      Settings.directory.mkdirs()
      PrintStream(File(Settings.directory, "beamline.log").outputStream(), true, Charsets.UTF_8)
    }.getOrNull()

  @Synchronized
  fun info(message: String) {
    val line = "${LocalTime.now().format(time)} $message"
    System.err.println(line)
    file?.println(line)
  }

  fun warn(message: String, error: Throwable? = null) = info("$message${error?.let { ": $it" } ?: ""}")
}
