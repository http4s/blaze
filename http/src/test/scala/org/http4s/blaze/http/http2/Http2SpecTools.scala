package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Exception.ErrorGenerator
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._

trait Http2SpecTools { self: Specification =>

  def await[T](awaitable: Awaitable[T]): T = Await.result(awaitable, 5.seconds)

  def zeroBuffer(size: Int): ByteBuffer = ByteBuffer.wrap(new Array(size))

  def connectionError(gen: ErrorGenerator): PartialFunction[Http2Result, MatchResult[_]] = {
    case Error(e: Http2SessionException) => e.code must_== gen.code
  }

  def streamError(stream: Int, gen: ErrorGenerator): PartialFunction[Http2Result, MatchResult[_]] = {
    case Error(e: Http2StreamException) if e.stream == stream => e.code must_== gen.code
  }

  lazy val ConnectionProtoError: PartialFunction[Http2Result, MatchResult[_]] =
    connectionError(Http2Exception.PROTOCOL_ERROR)
}
