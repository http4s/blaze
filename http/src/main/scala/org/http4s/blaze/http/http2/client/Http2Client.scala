package org.http4s.blaze.http.http2.client

import org.http4s.blaze.http.{HttpClient, HttpClientConfig, HttpClientImpl}
import org.http4s.blaze.http.http2.{Http2Settings, ImmutableHttp2Settings}

object Http2Client {
  private[this] val defaultSettings: ImmutableHttp2Settings =
    Http2Settings.default.copy(initialWindowSize = 256 * 1024)

  lazy val defaultH2Client: HttpClient = newH2Client()

  private[blaze] def newH2Client(): HttpClient = {
    val manager =
      Http2ClientSessionManagerImpl(HttpClientConfig.Default, defaultSettings)
    new HttpClientImpl(manager)
  }
}
