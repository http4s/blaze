/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

private[http] object ALPNTokens {
  // protocol strings for known HTTP implementations
  val HTTP_1_1 = "http/1.1"
  val H2 = "h2"
  val H2_14 = "h2-14"

  val AllTokens: Seq[String] = Seq(HTTP_1_1, H2, H2_14)
}
