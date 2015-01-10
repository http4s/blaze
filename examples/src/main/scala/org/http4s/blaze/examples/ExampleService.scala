package org.http4s.blaze.examples

import java.nio.ByteBuffer

import org.http4s.blaze.examples.http20.Http2Server._
import org.http4s.blaze.http._
import org.http4s.blaze.http.http20.{TupleHeaderEncoder, TupleHeaderDecoder, Http2ServerHubStage, BasicHttpStage}
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor
import org.http4s.blaze.util.Execution._

import scala.concurrent.Future
import scala.concurrent.duration.Duration

object ExampleService {

  def http1Stage(status: Option[IntervalConnectionMonitor], maxRequestLength: Int): HttpServerStage =
    new HttpServerStage(maxRequestLength)(service(status))

  def http2Stage(status: Option[IntervalConnectionMonitor], maxHeadersLength: Int): Http2ServerHubStage[Headers] = {
    def newNode(): LeafBuilder[Http2Meg] = LeafBuilder(new BasicHttpStage(Duration.Inf, trampoline, service(status)))
    new Http2ServerHubStage[Headers](
      new TupleHeaderDecoder(maxHeadersLength),
      new TupleHeaderEncoder(),
      newNode,
      Duration.Inf,
      300
    )
  }

  private def service(status: Option[IntervalConnectionMonitor])
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
