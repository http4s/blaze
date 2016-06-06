package org.http4s.blaze.examples

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel.ServerChannel
import org.http4s.blaze.http.{ResponseBuilder, _}
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor
import org.http4s.blaze.util.Execution

import scala.concurrent.Future

object ExampleService {

  private implicit val ec = Execution.trampoline

  def http1Stage(status: Option[IntervalConnectionMonitor], maxRequestLength: Int, channel: Option[AtomicReference[ServerChannel]] = None): HttpServerStage =
    new HttpServerStage(1024*1024, maxRequestLength)(service(status, channel))

  def service(status: Option[IntervalConnectionMonitor], channel: Option[AtomicReference[ServerChannel]] = None)
             (request: Request): ResponseBuilder = {
      request.uri match {
        case "/bigstring" =>
          Responses.Ok(bigstring, ("content-type", "application/binary")::Nil)

//        case "/chunkedstring" =>
//          val writer = responder(HttpResponsePrelude(200, "OK", Nil))
//
//          def go(i: Int): Future[writer.Finished] = {
//            if (i > 0) writer.write(ByteBuffer.wrap(s"i: $i\n".getBytes)).flatMap(_ => go(i-1))
//            else writer.close()
//          }
//          go(1024*10)

        case "/status" =>
          Responses.Ok(status.map(_.getStats().toString).getOrElse("Missing Status."))

        case "/kill" =>
          channel.flatMap(a => Option(a.get())).foreach(_.close())
          Responses.Ok("Killing connection.")

//        case "/echo" =>
//          val hs = request.headers.collect {
//            case h@(k, _) if k.equalsIgnoreCase("Content-type") => h
//          }
//
//          val writer = responder(HttpResponsePrelude(200, "OK", hs))
//          val body = request.body
//
//          def go(): Future[writer.Finished] = body().flatMap { chunk =>
//            if (chunk.hasRemaining) writer.write(chunk).flatMap(_ => go())
//            else writer.close()
//          }
//
//          go()

        case uri =>
          val sb = new StringBuilder
          sb.append("Hello world!\n")
            .append("Path: ").append(uri)
            .append("\nHeaders\n")
          request.headers.map { case (k, v) => "[\"" + k + "\", \"" + v + "\"]\n" }
            .addString(sb)

          val body = sb.result()
          Responses.Ok(body)
      }
    }

  private val bigstring = (0 to 1024*20).mkString("\n", "\n", "").getBytes()
}
