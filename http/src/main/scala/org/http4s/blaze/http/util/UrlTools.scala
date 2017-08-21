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

    def path: String = uri.getPath match {
      case "" | null => "/"
      case p => p
    }

    def fullPath: String =
      if (uri.getQuery != null) path + "?" + uri.getQuery
      else path

    def getAddress: InetSocketAddress = {
      val port =
        if (uri.getPort > 0) uri.getPort
        else (if (uri.getScheme.equalsIgnoreCase("http")) 80 else 443)
      new InetSocketAddress(uri.getHost, port)
    }
  }

  object UrlComposition {
    def apply(url: String): Try[UrlComposition] = Try {
      val uri = java.net.URI.create(if (isPrefixedWithHTTP(url)) url else "http://" + url)
      UrlComposition(uri)
    }
  }

  private[this] def isPrefixedWithHTTP(string: String): Boolean = {
    string.length >= 4 &&
      (string.charAt(0) == 'h' || string.charAt(0) == 'H') &&
      (string.charAt(1) == 't' || string.charAt(1) == 'T') &&
      (string.charAt(2) == 't' || string.charAt(2) == 'T') &&
      (string.charAt(3) == 'p' || string.charAt(3) == 'P')
  }
}
