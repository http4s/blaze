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
