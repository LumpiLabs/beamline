package io.github.lumpilabs.beamline.sender

import android.content.Context
import io.github.lumpilabs.beamline.protocol.VideoSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A TV to mirror to, found on the network or typed in. */
data class ReceiverAddress(val name: String, val host: String, val port: Int)

sealed interface MirrorStatus {
  data object Idle : MirrorStatus

  data class Connecting(val receiver: String) : MirrorStatus

  /** [codec] is a [io.github.lumpilabs.beamline.protocol.Codecs] name. */
  data class Mirroring(val receiver: String, val size: VideoSize, val codec: String) : MirrorStatus

  /** The connection was lost; capture goes on while the phone tries to reach the TV again. */
  data class Reconnecting(val receiver: String) : MirrorStatus

  /** First connection to this TV: does it show the same [code]? Answered in the app or the notification. */
  data class Pairing(val receiver: String, val code: String) : MirrorStatus

  /** The codes matched; now the TV's user has to allow this phone with OK on the remote. */
  data class AllowOnTv(val receiver: String, val code: String) : MirrorStatus

  data class Failed(val message: String) : MirrorStatus

  val isActive: Boolean
    get() = this is Connecting || this is Mirroring || this is Reconnecting || this is Pairing || this is AllowOnTv
}

/** "123456" as "123 456", easier to compare at a glance. */
fun spacedCode(code: String): String = code.chunked(3).joinToString(" ")

/** Shared between the mirroring service, the UI and the Quick Settings tile. */
object MirrorState {
  private val _status = MutableStateFlow<MirrorStatus>(MirrorStatus.Idle)
  val status: StateFlow<MirrorStatus> = _status

  fun set(context: Context, status: MirrorStatus) {
    _status.value = status
    MirrorTileService.requestUpdate(context)
  }
}
