package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

/** This needs to contain the state of the header Encoder */
trait HeaderEncoder[T] {

  /** Note that the default value is 4096 bytes */
  def getMaxTableSize(): Int

  def setMaxTableSize(max: Int): Unit

  def encodeHeaders(hs: T): ByteBuffer
}

