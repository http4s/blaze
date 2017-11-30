package org.http4s.blaze.http.http2

/** Represents a place for [[WriteInterest]]s to register their interested in writing data */
private trait WriteListener {

  /** Register a [[WriteInterest]] with this listener to be invoked later once it is
    * possible to write data to the outbound channel.
    *
    * @param interest the `WriteListener` with an interest in performing a write operation.
    */
  def registerWriteInterest(interest: WriteInterest): Unit
}

