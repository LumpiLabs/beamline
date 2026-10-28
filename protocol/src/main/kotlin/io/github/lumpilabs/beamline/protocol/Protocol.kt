package io.github.lumpilabs.beamline.protocol

/** Constants shared by the sender and the receiver. See docs/DESIGN.md. */
object Protocol {
  /** Highest protocol version this build speaks. */
  const val VERSION = 1

  /** DNS-SD service type the receiver registers. */
  const val SERVICE_TYPE = "_beamline._tcp"

  /** TXT record keys of the receiver's DNS-SD service. */
  object Txt {
    const val VERSION = "v"
    const val ID = "id"
    const val NAME = "n"
    const val CODECS = "c"
    const val RESOLUTION = "r"
  }
}

/** Codec names used in discovery records and control messages. */
object Codecs {
  const val H264 = "h264"
  const val HEVC = "hevc"
  const val OPUS = "opus"
  const val AAC = "aac"
}
