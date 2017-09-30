package org.http4s.blaze.examples

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel.ServerChannel
import org.http4s.blaze.http.util.HeaderNames
import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.Future

object ExampleService {

  private implicit val ec = Execution.trampoline

  def service(status: Option[IntervalConnectionMonitor], channel: Option[AtomicReference[ServerChannel]] = None)
             (request: HttpRequest): Future[RouteAction] = {
    Future.successful {
      request.url match {
        case "/plaintext" => RouteAction.Ok(helloWorld, (HeaderNames.ContentType -> "text/plain")::Nil)
        case "/ping" => RouteAction.Ok("pong")
        case "/bigstring" => RouteAction.Ok(bigstring)

        case "/chunkedstring" =>
          @volatile
          var i = 0
          RouteAction.Streaming(200, "OK", Nil) {
            Future.successful {
              if (i < 1000) {
                i += 1
                ByteBuffer.wrap(s"i: $i\n".getBytes(StandardCharsets.UTF_8))
              }
              else BufferTools.emptyBuffer
            }
          }

        case "/status" =>
          RouteAction.Ok(status.map(_.getStats().toString).getOrElse("Missing Status."))

        case "/kill" =>
          channel.flatMap(a => Option(a.get())).foreach(_.close())
          RouteAction.Ok("Killing server.")

        case "/echo" if request.method == "POST" =>
          val hs = request.headers.collect {
            case h@(k, _) if k.equalsIgnoreCase("content-type") => h
            case h@(k, _) if k.equalsIgnoreCase("content-length") => h
          }

          RouteAction.Streaming(200, "OK", hs) {
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

  private val bigstring = StandardCharsets.UTF_8.encode((0 to 1024*20).mkString("\n", "\n", ""))
  private val helloWorld = StandardCharsets.UTF_8.encode("Hello, world!")
}
