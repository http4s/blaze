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

package org.http4s.blaze.http

import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.{SSLContext, SSLEngine}

import org.http4s.blaze.util.GenericSSLContext

/** Configuration object for creating a [[HttpClient]]
  *
  * @param maxResponseLineLength maximum permissible length of the initial response line
  * @param maxHeadersLength maximum combined length of the headers
  * @param lenientParser whether to be lenient in HTTP/1.x parsing
  * @param channelGroup the `AsynchronousChannelGroup` used to multiplex connections on
  * @param sslContext `SSLContext` to use for secure connections
  */
case class HttpClientConfig(
    maxResponseLineLength: Int = 2 * 1048,
    maxHeadersLength: Int = 8 * 1024,
    lenientParser: Boolean = false,
    channelGroup: Option[AsynchronousChannelGroup] = None,
    sslContext: Option[SSLContext] = None) {
  private lazy val theSslContext =
    sslContext.getOrElse(GenericSSLContext.clientSSLContext())

  /** Get a new SSlEngine set to ClientMode.
    *
    * If the SslContext of this config is not defined, the default
    * will be used.
    */
  def getClientSslEngine(): SSLEngine = {
    val engine = theSslContext.createSSLEngine()
    engine.setUseClientMode(true)
    engine
  }
}

object HttpClientConfig {
  val Default: HttpClientConfig = HttpClientConfig()
}
