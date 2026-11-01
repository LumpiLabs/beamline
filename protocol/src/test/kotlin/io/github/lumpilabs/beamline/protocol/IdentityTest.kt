package io.github.lumpilabs.beamline.protocol

import java.nio.file.Files
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {
  @Test
  fun createsAValidSelfSignedCertificate() {
    val identity = Identity.generate("Beamline TV")
    val certificate = identity.certificate
    certificate.verify(certificate.publicKey)
    certificate.checkValidity()
    assertEquals(3, certificate.version)
    assertEquals("CN=Beamline TV", certificate.subjectX500Principal.name)
    assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
    assertEquals("SHA256withECDSA", certificate.sigAlgName)
    assertTrue(certificate.serialNumber.signum() > 0)
  }

  @Test
  fun theKeyBelongsToTheCertificate() {
    val identity = Identity.generate("Beamline phone")
    val data = "pairing".encodeToByteArray()
    val signature = Signature.getInstance("SHA256withECDSA").run {
      initSign(identity.privateKey)
      update(data)
      sign()
    }
    val verifier = Signature.getInstance("SHA256withECDSA").apply {
      initVerify(identity.certificate.publicKey)
      update(data)
    }
    assertTrue(verifier.verify(signature))
  }

  @Test
  fun everyInstallGetsItsOwnIdentity() {
    assertNotEquals(Identity.generate("Beamline TV").fingerprint, Identity.generate("Beamline TV").fingerprint)
  }

  @Test
  fun theFingerprintIsSha256InHex() {
    val fingerprint = Identity.generate("Beamline TV").fingerprint
    assertEquals(64, fingerprint.length)
    assertTrue(fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
  }

  @Test
  fun isKeptAcrossStarts() {
    val directory = Files.createTempDirectory("beamline-identity").toFile()
    try {
      val first = Identity.loadOrCreate(directory, "Beamline TV")
      val again = Identity.loadOrCreate(directory, "Beamline TV")
      assertEquals(first.fingerprint, again.fingerprint)
      assertContentEquals(first.privateKey.encoded, again.privateKey.encoded)
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun aDamagedIdentityIsReplaced() {
    val directory = Files.createTempDirectory("beamline-identity").toFile()
    try {
      val first = Identity.loadOrCreate(directory, "Beamline TV")
      directory.resolve("identity.crt").writeBytes(byteArrayOf(1, 2, 3))
      assertNotEquals(first.fingerprint, Identity.loadOrCreate(directory, "Beamline TV").fingerprint)
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun derLengthsAboveOneByte() {
    assertContentEquals(byteArrayOf(0x04, 0x81.toByte(), 200.toByte()), Der.tlv(0x04, ByteArray(200)).copyOf(3))
    assertContentEquals(byteArrayOf(0x04, 0x82.toByte(), 0x01, 0x2C), Der.tlv(0x04, ByteArray(300)).copyOf(4))
  }

  @Test
  fun derObjectIdentifiers() {
    // ecdsa-with-SHA256, as in RFC 5758.
    val expected = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    assertContentEquals(expected, Der.oid("1.2.840.10045.4.3.2"))
  }
}
