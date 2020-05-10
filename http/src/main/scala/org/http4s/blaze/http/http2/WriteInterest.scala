/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

/** Type that can be polled for the ability to write bytes */
private trait WriteInterest {

  /** Invoked by the [[WriteController]] that this `WriteInterest` is registered with.
    *
    * Before being invoked, `this` must be unregistered from the [[WriteController]] and
    * it is safe to add `this` back as an interest before returning the corresponding
    * data, if desired.
    *
    * @note this method will be called by the `WriteController` from within
    *       the sessions serial executor.
    */
  def performStreamWrite(): collection.Seq[ByteBuffer]
}
