/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

import org.http4s.blaze.util.{FutureEOF, FutureUnit}

private[http] object InternalWriter {
  val CachedSuccess = FutureUnit
  val ClosedChannelException = FutureEOF
  val BufferLimit = 32 * 1024
}
