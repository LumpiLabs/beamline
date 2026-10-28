package io.github.lumpilabs.beamline.protocol

/** Constants shared by the sender and the receiver. See docs/DESIGN.md. */
object Protocol {
  /** Highest protocol version this build speaks. */
  const val VERSION = 1

  /** DNS-SD service type the receiver registers. */
  const val SERVICE_TYPE = "_beamline._tcp"

  /** TCP port the receiver listens on, so a manually entered address works without discovery. */
  const val DEFAULT_PORT = 47800

  /** Highest version both sides speak, or null if [offered] has none this build supports. */
  fun negotiate(offered: List<Int>): Int? = offered.filter { it in 1..VERSION }.maxOrNull()

  /** TXT record keys of the receiver's DNS-SD service. */
  object Txt {
    const val VERSION = "v"
    const val ID = "id"
    const val NAME = "n"
    const val CODECS = "c"
    const val RESOLUTION = "r"
  }
}

/** Reasons in [Bye] that the other side acts on; any other reason is shown as it is. */
object ByeReasons {
  /** Receiver: it is showing another sender. */
  const val BUSY = "busy"

  /** Receiver, while its app is closed: this sender never connected while the app was open. */
  const val OPEN_APP = "open_app"

  /** Receiver: the user left the TV app. */
  const val APP_CLOSED = "app_closed"

  /** Receiver: its screen didn't come up to show the picture. */
  const val NO_SCREEN = "no_screen"
}

/** Codec names used in discovery records and control messages. */
object Codecs {
  const val H264 = "h264"
  const val HEVC = "hevc"
  const val OPUS = "opus"
  const val AAC = "aac"
}
