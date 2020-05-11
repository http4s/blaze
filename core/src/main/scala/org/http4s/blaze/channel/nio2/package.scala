/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.channel

package object nio2 {

  /** Default buffer size use for IO operations */
  private[nio2] val DefaultBufferSize: Int = 8 * 1024 // 8 KB
}
