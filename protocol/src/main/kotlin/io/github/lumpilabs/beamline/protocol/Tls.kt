package io.github.lumpilabs.beamline.protocol

import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS for Beamline connections. Both sides present their own self-signed [Identity], and the
 * handshake accepts any certificate: there is no authority that could vouch for a TV or a phone.
 * Whom to trust is decided afterwards, from the peer's fingerprint and, the first time, by the
 * user comparing the pairing code on both screens.
 */
object Tls {
  private val PASSWORD = CharArray(0)

  fun context(identity: Identity): SSLContext {
    val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setKeyEntry("beamline", identity.privateKey, PASSWORD, arrayOf(identity.certificate))
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }.keyManagers
    return SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(AnyCertificate), null) }
  }

  /** An unbound server socket that asks every client for its certificate. */
  fun serverSocket(context: SSLContext): SSLServerSocket =
    (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
      needClientAuth = true
      enabledProtocols = modern(supportedProtocols)
    }

  /** Wraps the connected [socket] in TLS as the client and runs the handshake. */
  fun client(context: SSLContext, socket: Socket, host: String, port: Int): SSLSocket =
    (context.socketFactory.createSocket(socket, host, port, true) as SSLSocket).apply {
      enabledProtocols = modern(supportedProtocols)
      startHandshake()
    }

  /** The DER certificate the other side presented in the handshake. */
  fun peerCertificate(socket: SSLSocket): ByteArray = socket.session.peerCertificates.first().encoded

  /** TLS 1.2 and newer; Android 7.1, the oldest TV, has 1.2. */
  private fun modern(supported: Array<String>): Array<String> =
    supported.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()

  /** See the class comment: checking happens after the handshake, by fingerprint. */
  private object AnyCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }
}
