package org.http4s.blaze.http.client

import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.http4s.blaze.http.Headers
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

/** HTTP response received by the client
  *
  * @param code Response code
  * @param status Response message. This have no meaning for the HTTP connection, its just for human enjoyment.
  * @param headers Response headers
  * @param body Function for obtaining the response body. Each invocation will return the __next__ body chunk
  *             with termination signaled by an __empty__ `ByteBuffer` as defined by `ByteBuffer.hasRemaining()`.
  */
case class ClientResponse(code: Int, status: String, headers: Headers, body: () => Future[ByteBuffer]) {
  import ClientResponse._
  def stringBody(): Future[String] = {
    val acc = new ArrayBuffer[ByteBuffer](8)

    def go(): Future[String] = body().flatMap { buffer =>
      if (buffer.hasRemaining) {
        acc += buffer
        go()
      }
      else {
        val b = BufferTools.joinBuffers(acc)
        val encoding = getCharset(headers)

        try {
          val bodyString = Charset.forName(encoding).decode(b).toString()
          Future.successful(bodyString)
        }
        catch { case e: Throwable => Future.failed(e) }
      }
    }(Execution.trampoline)

    go()
  }

  private def getCharset(hs: Headers): String = {
    headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("content-type") => charsetRegex.findFirstIn(v)
    }.flatten.getOrElse("UTF-8")
  }


}

object ClientResponse {
  private val charsetRegex = "(?<=charset=)[^;]*".r
}
