package org.http4s.blaze.http.http20

import org.http4s.blaze.http.http20.Settings.DefaultSettings

import scala.collection.mutable.ArrayBuffer

/** HeaderDecoder that results in a Seq[(String, String)] */
final class TupleHeaderDecoder(maxHeaderSize: Int,
                              maxHeaderTable: Int  = DefaultSettings.HEADER_TABLE_SIZE)
  extends HeaderDecoder[Seq[(String, String)]](maxHeaderSize, maxHeaderTable) {

  private var acc = new ArrayBuffer[(String, String)]

  override def addHeader(name: String, value: String, sensitive: Boolean): Unit = acc += ((name, value))

  /** Returns the header collection and clears the builder */
  override def result(): Seq[(String, String)] = {
    val r = acc
    acc = new ArrayBuffer[(String, String)](r.size + 10)
    r
  }
}
