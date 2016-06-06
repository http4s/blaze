package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future


package object client {

  case class ClientResponse(code: Int, status: String, headers: Headers, body: () => Future[ByteBuffer]) {
    def stringBody(): Future[String] = {
      val acc = new ArrayBuffer[ByteBuffer](8)

      def go(): Future[String] = body().flatMap { buffer =>
        if (buffer.hasRemaining) {
          acc += buffer
          go()
        }
        else {
          val b = BufferTools.joinBuffers(acc)
          val encoding = headers.collectFirst {
            case (k, v) if k.equalsIgnoreCase("content-type") => "UTF-8" // TODO: this needs to examine the header
          }.getOrElse("UTF-8")

          try {
            val bodyString = Charset.forName(encoding).decode(b).toString()
            Future.successful(bodyString)
          }
          catch { case e: Throwable => Future.failed(e) }
        }
      }(Execution.trampoline)

      go()
    }
  }
}
