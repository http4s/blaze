package org.http4s.blaze.http.http2

import java.util.Locale

import scala.collection.generic.Growable

private object StageTools {

  /** Copy HTTP headers from `source` to dest
    *
    * The keys of `source` are converted to lower case to conform with the HTTP/2 spec.
    * https://tools.ietf.org/html/rfc7540#section-8.1.2
    */
  def copyHeaders(source: Iterable[(String, String)], dest: Growable[(String, String)]): Unit =
    source.foreach {
      case p @ (k, v) =>
        val lowerKey = k.toLowerCase(Locale.ENGLISH)
        if (lowerKey eq k) dest += p // don't need to make a new Tuple2
        else dest += lowerKey -> v
    }
}
