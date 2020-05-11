/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.examples

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import org.http4s.blaze.util.BogusKeystore

object ExampleKeystore {
  def sslContext(): SSLContext = {
    val ksStream = BogusKeystore.asInputStream()
    assert(ksStream != null)

    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, BogusKeystore.getCertificatePassword)

    val context = SSLContext.getInstance("SSL")

    context.init(kmf.getKeyManagers(), null, null)

    context
  }

  def clientAuthSslContext(): SSLContext = {
    /*
      keys were generated using the script at
      http://www.chesterproductions.net.nz/blogs/it/code/configuring-client-certificate-authentication-with-tomcat-and-java/537/
      In order to access the server, you need to import the 'client.p12' file into your browsers certificate store.
     */

    val ksStream =
      getClass.getResourceAsStream("/clientauth/server.jks") //BogusKeystore.asInputStream()
    assert(ksStream != null)

    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, "password".toCharArray)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, "password".toCharArray)

    val context = SSLContext.getInstance("SSL")

    // the keystore is also used as the trust manager
    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)

    context.init(kmf.getKeyManagers(), tmf.getTrustManagers, null)

    context
  }
}
