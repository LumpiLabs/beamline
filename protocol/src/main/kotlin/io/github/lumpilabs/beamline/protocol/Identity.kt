package io.github.lumpilabs.beamline.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The key and self-signed certificate one install presents in TLS: EC P-256, created on first
 * start and kept. Peers recognise each other by the certificate's [fingerprint].
 */
class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
  val certificateDer: ByteArray
    get() = certificate.encoded

  val fingerprint: String
    get() = fingerprintOf(certificateDer)

  companion object {
    private const val KEY_FILE = "identity.key"
    private const val CERTIFICATE_FILE = "identity.crt"
    private const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val COMMON_NAME = "2.5.4.3"
    private val lock = Any()

    /** SHA-256 of a DER certificate, in lowercase hex. */
    fun fingerprintOf(certificateDer: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(certificateDer).joinToString("") { "%02x".format(it) }

    fun generate(commonName: String): Identity {
      val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
      val name = Der.sequence(Der.set(Der.sequence(Der.oid(COMMON_NAME), Der.utf8(commonName))))
      val algorithm = Der.sequence(Der.oid(ECDSA_WITH_SHA256))
      // Positive, without a leading zero byte: DER wants integers in their shortest form.
      val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }.apply { this[0] = ((this[0].toInt() and 0x3F) or 0x40).toByte() }
      // A day back in case the clocks of the two devices differ. Peers trust fingerprints, not
      // dates, so the certificate never needs to expire.
      val notBefore = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
      val tbs = Der.sequence(
        Der.explicit(0, Der.integer(byteArrayOf(2))),
        Der.integer(serial),
        algorithm,
        name,
        Der.sequence(Der.utcTime(notBefore), Der.generalizedTimeForever()),
        name,
        keys.public.encoded,
      )
      val signature = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(tbs)
        sign()
      }
      val certificate = Der.sequence(tbs, algorithm, Der.bitString(signature))
      return Identity(keys.private, parseCertificate(certificate))
    }

    fun decode(privateKeyPkcs8: ByteArray, certificateDer: ByteArray): Identity =
      Identity(
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8)),
        parseCertificate(certificateDer),
      )

    /**
     * The identity kept in [directory], or a new one that is kept there from now on. A damaged
     * one is replaced, which means pairing again.
     */
    fun loadOrCreate(directory: File, commonName: String): Identity =
      synchronized(lock) {
        val keyFile = File(directory, KEY_FILE)
        val certificateFile = File(directory, CERTIFICATE_FILE)
        if (keyFile.isFile && certificateFile.isFile) {
          runCatching { decode(keyFile.readBytes(), certificateFile.readBytes()) }.getOrNull()?.let { return it }
        }
        val identity = generate(commonName)
        directory.mkdirs()
        writeReplacing(keyFile, identity.privateKey.encoded)
        writeReplacing(certificateFile, identity.certificateDer)
        identity
      }

    private fun parseCertificate(der: ByteArray): X509Certificate =
      CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /** Writes next to [file] first, so a crash never leaves half a key behind. */
    private fun writeReplacing(file: File, bytes: ByteArray) {
      val temporary = File(file.parentFile, "${file.name}.new")
      temporary.writeBytes(bytes)
      // Android replaces the target in one step; Windows (the test sender) needs it gone first.
      if (!temporary.renameTo(file)) {
        file.delete()
        check(temporary.renameTo(file)) { "Can't write $file" }
      }
    }
  }
}

/** Just enough DER to write a self-signed X.509 certificate. */
internal object Der {
  fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

  fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

  /** [value] is the big-endian two's complement form, already as short as it can be. */
  fun integer(value: ByteArray): ByteArray = tlv(0x02, value)

  fun utf8(text: String): ByteArray = tlv(0x0C, text.encodeToByteArray())

  fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

  fun explicit(number: Int, content: ByteArray): ByteArray = tlv(0xA0 or number, content)

  fun utcTime(date: Date): ByteArray = tlv(0x17, format("yyMMddHHmmss'Z'", date).encodeToByteArray())

  /** RFC 5280's value for a certificate without a well-defined expiry date. */
  fun generalizedTimeForever(): ByteArray = tlv(0x18, "99991231235959Z".encodeToByteArray())

  fun oid(dotted: String): ByteArray {
    val arcs = dotted.split('.').map(String::toLong)
    val body = ByteArrayOutputStream()
    body.write((arcs[0] * 40 + arcs[1]).toInt())
    for (arc in arcs.drop(2)) {
      val septets = generateSequence(arc) { (it shr 7).takeIf { rest -> rest > 0 } }.map { (it and 0x7F).toInt() }.toList().reversed()
      septets.forEachIndexed { i, septet -> body.write(if (i < septets.lastIndex) septet or 0x80 else septet) }
    }
    return tlv(0x06, body.toByteArray())
  }

  fun tlv(tag: Int, content: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(tag)
    val length = content.size
    when {
      length < 0x80 -> out.write(length)
      length <= 0xFF -> { out.write(0x81); out.write(length) }
      length <= 0xFFFF -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xFF) }
      else -> { out.write(0x83); out.write(length shr 16); out.write((length shr 8) and 0xFF); out.write(length and 0xFF) }
    }
    out.write(content)
    return out.toByteArray()
  }

  private fun concat(parts: Array<out ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    parts.forEach(out::write)
    return out.toByteArray()
  }

  private fun format(pattern: String, date: Date): String =
    SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
}
