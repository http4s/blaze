package org.http4s.blaze.examples

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}

import org.http4s.blaze.util.BogusKeystore

object ExampleKeystore {

  def sslContext(): SSLContext = {
    val ksStream = BogusKeystore.asInputStream()

    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, BogusKeystore.getCertificatePassword)

    val context = SSLContext.getInstance("SSL")

    context.init(kmf.getKeyManagers(), null, null)

    context
  }
}
