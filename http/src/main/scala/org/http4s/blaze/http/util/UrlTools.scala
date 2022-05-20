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

package org.http4s.blaze.http.util

import java.net.{InetSocketAddress, URI}

import scala.util.Try

private[blaze] object UrlTools {
  // TODO: we need to make sure to validate this
  case class UrlComposition(uri: URI) {

    /** Lower case representation of the scheme */
    val scheme: String = uri.getScheme.toLowerCase

    /** Lower case representation of the authority */
    val authority: String = uri.getAuthority.toLowerCase

    def isTls: Boolean = scheme == "https"

    def path: String =
      uri.getPath match {
        case "" | null => "/"
        case p => p
      }

    def fullPath: String =
      if (uri.getQuery != null) path + "?" + uri.getQuery
      else path

    def getAddress: InetSocketAddress = {
      val port =
        if (uri.getPort > 0) uri.getPort
        else (if (uri.getScheme.equalsIgnoreCase("http")) 80
              else 443)
      new InetSocketAddress(uri.getHost, port)
    }
  }

  object UrlComposition {
    def apply(url: String): Try[UrlComposition] =
      Try {
        val uri = java.net.URI
          .create(if (isPrefixedWithHTTP(url)) url else "http://" + url)
        UrlComposition(uri)
      }
  }

  private[this] def isPrefixedWithHTTP(string: String): Boolean =
    string.regionMatches(true, 0, "http", 0, 4)
}
