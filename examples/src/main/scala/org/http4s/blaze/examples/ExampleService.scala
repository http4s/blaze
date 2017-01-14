package org.http4s.blaze.examples

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel.ServerChannel
import org.http4s.blaze.http.{ResponseBuilder, _}
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.Future

object ExampleService {

  private implicit val ec = Execution.trampoline

  def http1Stage(status: Option[IntervalConnectionMonitor],
                 config: HttpServerConfig,
                 channel: Option[AtomicReference[ServerChannel]] = None): HttpServerStage =


  def service(status: Option[IntervalConnectionMonitor], channel: Option[AtomicReference[ServerChannel]] = None)
             (request: HttpRequest): Future[ResponseBuilder] = {
    if (request.method == "POST") {
      // Accumulate the body. I think this should be made easier to do
      request.body.accumulate().map { body =>
        val bodyString = StandardCharsets.UTF_8.decode(body)
        RouteAction.Ok(s"You sent: $bodyString")
      }
    }
    else Future.successful {
      request.uri match {
        case "/ping" => RouteAction.Ok("pong")
        case "/bigstring" => RouteAction.Ok(bigstring)

        case "/chunkedstring" =>

          val body = {
            var i = 0
            () => Future.successful {
              if (i < 1000) {
                i += 1
                ByteBuffer.wrap(s"i: $i\n".getBytes)
              }
              else BufferTools.emptyBuffer
            }
          }

          RouteAction.Streaming(200, "OK", Nil)(body)

        case "/status" =>
          RouteAction.Ok(status.map(_.getStats().toString).getOrElse("Missing Status."))

        case "/kill" =>
          channel.flatMap(a => Option(a.get())).foreach(_.close())
          RouteAction.Ok("Killing server.")

        case "/echo" =>
          val hs = request.headers.collect {
            case h@(k, _) if k.equalsIgnoreCase("Content-type") => h
          }

          RouteAction.Streaming(200, "OK", hs) { () =>
            request.body()
          }

        case uri =>
          val sb = new StringBuilder
          sb.append("Hello world!\n")
            .append("Path: ").append(uri)
            .append("\nHeaders\n")
          request.headers.map { case (k, v) => "[\"" + k + "\", \"" + v + "\"]\n" }
            .addString(sb)

          val body = sb.result()
          RouteAction.Ok(body)
      }
    }
  }

  private val bigstring = (0 to 1024*20).mkString("\n", "\n", "").getBytes()
}
