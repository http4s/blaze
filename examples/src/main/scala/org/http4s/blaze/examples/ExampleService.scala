package org.http4s.blaze.examples

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor

import scala.concurrent.Future

object ExampleService {

  def http1Stage(status: Option[IntervalConnectionMonitor], maxRequestLength: Int): HttpServerStage =
    new HttpServerStage(maxRequestLength)(service(status))

  def service(status: Option[IntervalConnectionMonitor])
             (method: Method, uri: Uri, hs: Headers, body: ByteBuffer): Future[Response] = {

    val resp = uri match {
      case "/bigstring" =>
        SimpleHttpResponse.Ok(bigstring, ("content-type", "application/binary")::Nil)

      case "/status" =>
        SimpleHttpResponse.Ok(status.map(_.getStats().toString).getOrElse("Missing Status."))

      case uri =>
        val sb = new StringBuilder
        sb.append("Hello world!\n")
          .append("Path: ").append(uri)
          .append("\nHeaders\n")
        hs.map { case (k, v) => "[\"" + k + "\", \"" + v + "\"]\n" }
          .addString(sb)

        val body = sb.result()
        SimpleHttpResponse.Ok(body)
    }

    Future.successful(resp)
  }

  private val bigstring = (0 to 1024*1024*2).mkString("\n", "\n", "").getBytes()
}
