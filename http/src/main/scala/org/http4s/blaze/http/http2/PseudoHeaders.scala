/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

/** HTTP/2 pseudo headers */
object PseudoHeaders {
  // Request pseudo headers
  val Method = ":method"
  val Scheme = ":scheme"
  val Path = ":path"
  val Authority = ":authority"

  // Response pseudo header
  val Status = ":status"
}
