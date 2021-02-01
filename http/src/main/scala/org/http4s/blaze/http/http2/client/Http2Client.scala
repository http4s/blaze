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
