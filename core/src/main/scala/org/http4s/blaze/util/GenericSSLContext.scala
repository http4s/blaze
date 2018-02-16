package org.http4s.blaze.util

import java.security.{KeyStore, NoSuchAlgorithmException, SecureRandom}
import java.security.cert.X509Certificate
import javax.net.ssl.{KeyManagerFactory, SSLContext, X509TrustManager}

/** SSL tools */
object GenericSSLContext {

  private class DefaultTrustManager extends X509TrustManager {
    def getAcceptedIssuers(): Array[X509Certificate] =
      new Array[java.security.cert.X509Certificate](0)
    def checkClientTrusted(certs: Array[X509Certificate], authType: String): Unit = {}
    def checkServerTrusted(certs: Array[X509Certificate], authType: String): Unit = {}
  }

  /** Create a default SSLContext with an empty trust manager */
  def clientSSLContext(): SSLContext =
    try {
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(null, Array(new DefaultTrustManager()), new SecureRandom())
      sslContext
    } catch {
      case e: NoSuchAlgorithmException =>
        throw new ExceptionInInitializerError(e)
      case e: ExceptionInInitializerError =>
        throw new ExceptionInInitializerError(e)
    }

  /** Create a default SSLContext with a fake key pair
    *
    * This is __NOT__ safe to use in a secure environment.
    */
  def serverSSLContext(): SSLContext = {
    val ksStream = BogusKeystore.asInputStream()
    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, BogusKeystore.getCertificatePassword)

    val context = SSLContext.getInstance("SSL")

    context.init(kmf.getKeyManagers(), null, null)
    context
  }

}
