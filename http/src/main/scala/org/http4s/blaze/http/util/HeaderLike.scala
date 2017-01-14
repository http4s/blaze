package org.http4s.blaze.http.util

/**
  * Operations that are useful for packing and unpacking header representations
  */
trait HeaderLike[T] {
  def make(key: String, value: String): T
  def getKey(header: T): String
  def getValue(header: T): String
}

object HeaderLike {

  def apply[T](implicit hl: HeaderLike[T]): HeaderLike[T] = hl

  implicit val tupleHeaderLike: HeaderLike[(String, String)] = new HeaderLike[(String, String)] {
    override def make(key: String, value: String): (String, String) = ((key, value))
    override def getKey(header: (String, String)): String = header._1
    override def getValue(header: (String, String)): String = header._2
  }
}