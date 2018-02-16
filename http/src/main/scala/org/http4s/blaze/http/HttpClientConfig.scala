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
