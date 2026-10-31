package io.github.lumpilabs.beamline.protocol

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TlsTest {
  private val tv = Identity.generate("Beamline TV")
  private val phone = Identity.generate("Beamline phone")

  @Test
  fun bothSidesSeeEachOthersCertificateAndTheSameCode() {
    val server = Tls.serverSocket(Tls.context(tv)).apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    val seenByTv = CompletableFuture<ByteArray>()
    val received = CompletableFuture<Frame>()
    thread(isDaemon = true) {
      server.accept().use { socket ->
        socket as SSLSocket
        socket.startHandshake()
        seenByTv.complete(Tls.peerCertificate(socket))
        val frame = FrameReader(socket.getInputStream()).read()!!
        received.complete(frame)
        FrameWriter(socket.getOutputStream()).write(ControlCodec.toFrame(PairingConfirm))
      }
    }

    val plain = Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 5_000) }
    Tls.client(Tls.context(phone), plain, "127.0.0.1", server.localPort).use { socket ->
      val seenByPhone = Tls.peerCertificate(socket)
      FrameWriter(socket.getOutputStream()).write(ControlCodec.toFrame(Pairing(receiverConfirms = true)))
      val reply = FrameReader(socket.getInputStream()).read()!!

      assertContentEquals(tv.certificateDer, seenByPhone)
      assertContentEquals(phone.certificateDer, seenByTv.get(5, TimeUnit.SECONDS))
      assertEquals(Pairing(receiverConfirms = true), ControlCodec.decode(received.get(5, TimeUnit.SECONDS).body))
      assertEquals(PairingConfirm, ControlCodec.decode(reply.body))
      assertTrue(socket.session.protocol == "TLSv1.3" || socket.session.protocol == "TLSv1.2")
      // Each side derives the code from both certificates, so both screens agree.
      assertEquals(
        PairingCode.derive(seenByPhone, phone.certificateDer),
        PairingCode.derive(tv.certificateDer, seenByTv.get(5, TimeUnit.SECONDS)),
      )
    }
    server.close()
  }
}
