/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

/** Representation of outbound streams
  *
  * For outbound streams we need a concrete StreamState to send messages
  * to, but we can't expect that we will have HEADERS to send right when
  * it is born, so we need to make the stream ID lazy since they must be
  * used in monotonically increasing order.
  *
  * @note this is a marker trait
  */
private trait OutboundStreamState extends StreamState
