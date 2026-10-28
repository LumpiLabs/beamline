package io.github.lumpilabs.beamline.sender

import io.github.lumpilabs.beamline.protocol.VideoSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A TV to mirror to, found on the network or typed in. */
data class ReceiverAddress(val name: String, val host: String, val port: Int)

sealed interface MirrorStatus {
  data object Idle : MirrorStatus

  data class Connecting(val receiver: String) : MirrorStatus

  data class Mirroring(val receiver: String, val size: VideoSize) : MirrorStatus

  data class Failed(val message: String) : MirrorStatus
}

/** Shared between the mirroring service and the UI. */
object MirrorState {
  private val _status = MutableStateFlow<MirrorStatus>(MirrorStatus.Idle)
  val status: StateFlow<MirrorStatus> = _status

  fun set(status: MirrorStatus) {
    _status.value = status
  }
}
