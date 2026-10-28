package io.github.lumpilabs.beamline.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The 6-digit code both screens show on first pairing (numeric comparison).
 *
 * `SHA-256("beamline-pairing-v1" || u32(len(r)) || r || u32(len(s)) || s)`, where `r` and `s`
 * are the DER certificates of the receiver and the sender. The first four bytes of the hash,
 * read as an unsigned big-endian integer, modulo 1,000,000, zero-padded to six digits.
 */
object PairingCode {
  private val DOMAIN = "beamline-pairing-v1".encodeToByteArray()

  fun derive(receiverCertificate: ByteArray, senderCertificate: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(DOMAIN)
    digest.update(lengthPrefix(receiverCertificate.size))
    digest.update(receiverCertificate)
    digest.update(lengthPrefix(senderCertificate.size))
    digest.update(senderCertificate)
    val hash = digest.digest()
    val value = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xFFFF_FFFFL
    return (value % 1_000_000).toString().padStart(6, '0')
  }

  private fun lengthPrefix(length: Int): ByteArray = ByteBuffer.allocate(4).putInt(length).array()
}
