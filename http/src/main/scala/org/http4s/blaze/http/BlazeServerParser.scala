package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.http.parser.Http1ServerParser
import org.http4s.blaze.http.util.HeaderLike
import org.http4s.blaze.util.BufferTools

import scala.collection.immutable.VectorBuilder

private[blaze] object BlazeServerParser {

  final case class Prelude[Headers](
     method: String,
     uri: String,
     majorVersion: Int,
     minorVersion: Int,
     headers: Iterable[Headers])
}

private[blaze] final class BlazeServerParser[Header](maxNonBody: Int)(implicit hl: HeaderLike[Header])
  extends Http1ServerParser(maxNonBody, maxNonBody, 2*1024) {

  private[this] var uri: String = null
  private[this] var method: String = null
  private[this] var minor: Int = -1
  private[this] var major: Int = -1
  private[this] val headers = new VectorBuilder[Header]

  private[this] def resetState(): Unit = {
    this.uri = null
    this.method = null
    this.major = -1
    this.minor = -1
    headers.clear()
  }

  override protected def submitRequestLine(
    methodString: String,
    uri: String,
    scheme: String,
    majorversion: Int,
    minorversion: Int
  ): Boolean = {
    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion

    false
  }

  override protected def headerComplete(name: String, value: String): Boolean = {
    headers += hl.make(name, value)
    false
  }

  def getMinorVersion(): Int = this.minor

  override def reset(): Unit = {
    resetState()
    super.reset()
  }

  // Return value of `true` means the prelude is complete
  def parsePrelude(buffer: ByteBuffer): Boolean = {
    if (!requestLineComplete() && !parseRequestLine(buffer)) {
      return false
    }

    if (!headersComplete() && !parseHeaders(buffer)) {
      return false
    }

    true
  }

  /**
    * Parses the body of a message. The result will never be `null`
    * but may be empty.
    */
  def parseBody(buffer: ByteBuffer): ByteBuffer = parseContent(buffer) match {
    case null => BufferTools.emptyBuffer
    case buff => buff
  }

  /**
    * Get the request prelude
    */
  def getRequestPrelude(): BlazeServerParser.Prelude[Header] = {
    val hs = headers.result()
    headers.clear()

    BlazeServerParser.Prelude(method, uri, major, minor, hs)
  }
}
