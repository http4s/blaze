/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
      getClass.getResourceAsStream("/clientauth/server.jks") // BogusKeystore.asInputStream()
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
