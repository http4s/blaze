package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

/** Type that can be polled for the ability to write bytes */
private trait WriteInterest {

  /** Invoked by the [[WriteListener]] that this `WriteInterest` is registered with.
    *
    * Before being invoked, `this` must be unregistered from the [[WriteListener]] and
    * it is safe to add `this` back as an interest before returning the corresponding
    * data, if desired.
    *
    * @note this method will be called by the `WriteController` from within
    *       the sessions serial executor.
    */
  def performStreamWrite(): Seq[ByteBuffer]

  /** Called to notify the `WriteInterest` of failure */
  def writeFailure(t: Throwable): Unit
}

// TODO: this should get its own file
/** Represents a place for [[WriteInterest]]s to register their interested in writing data */
private trait WriteListener {

  /** Register a [[WriteInterest]] with this listener to be invoked later once it is
    * possible to write data to the outbound channel.
    *
    * @param interest the `WriteListener` with an interest in performing a write operation.
    */
  def registerWriteInterest(interest: WriteInterest): Unit

  /** Remove a [[WriteInterest]] from the `WriteListener`
    *
    * @param interest to be removed
    * @return true of the interest was registered and removed, false otherwise.
    */
  def removeInterest(interest: WriteInterest): Boolean
}

