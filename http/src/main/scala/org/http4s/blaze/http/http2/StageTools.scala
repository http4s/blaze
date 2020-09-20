/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.util.Locale
import scala.collection.mutable.{ArrayBuffer, Builder}

private[http2] object StageTools {
  // There are two copies of this because `Growable` moved in Scala
  // 2.13 and we're stuck with the deprecation warning.  It's private
  // anyway.  Reunify on Growable after Scala 2.12 is dropped.

  /** Copy HTTP headers from `source` to dest
    *
    * The keys of `source` are converted to lower case to conform with the HTTP/2 spec.
    * https://tools.ietf.org/html/rfc7540#section-8.1.2
    */
  def copyHeaders[F](source: Iterable[(String, String)], dest: Builder[(String, String), F]): Unit =
    source.foreach { case p @ (k, v) =>
      val lowerKey = k.toLowerCase(Locale.ENGLISH)
      if (lowerKey eq k) dest += p // don't need to make a new Tuple2
      else dest += lowerKey -> v
    }

  /** Copy HTTP headers from `source` to dest
    *
    * The keys of `source` are converted to lower case to conform with the HTTP/2 spec.
    * https://tools.ietf.org/html/rfc7540#section-8.1.2
    */
  def copyHeaders(source: Iterable[(String, String)], dest: ArrayBuffer[(String, String)]): Unit =
    source.foreach { case p @ (k, v) =>
      val lowerKey = k.toLowerCase(Locale.ENGLISH)
      if (lowerKey eq k) dest += p // don't need to make a new Tuple2
      else dest += lowerKey -> v
    }
}
