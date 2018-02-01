package org.http4s.blaze.examples.http2

import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpClient
import org.http4s.blaze.http.http2.client.Http2Client

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


abstract class H2ClientExample(count: Int, timeout: Duration) {

  protected implicit val ec = scala.concurrent.ExecutionContext.global

  lazy val h2Clients: Array[HttpClient] = Array.tabulate(3){_ => Http2Client.newH2Client() }

  def doCall(tag: Int): Future[Int]

  def main(args: Array[String]): Unit = {
    println("Hello, world!")

    Await.result(Future.sequence((0 until h2Clients.length).map(doCall)), 5.seconds)

    def fresps(i: Int) = (h2Clients.length until i).map { i =>
      doCall(i).map(i -> _)
    }

    Await.result(Future.sequence(fresps(count / 5)), timeout)
    val start = System.currentTimeMillis
    val resps = Await.result(Future.sequence(fresps(count)), timeout)
    val duration = System.currentTimeMillis - start

    val chars = resps.foldLeft(0){ case (acc, (i, len)) =>
      acc + len
    }

    println(s"The total body length of ${resps.length} messages: $chars. Took $duration millis")
  }
}

object H2GoogleExample extends H2ClientExample(20, 30.seconds) {
  override def doCall(tag: Int): Future[Int] = callGoogle(tag).map(_.length)

  private[this] def callGoogle(tag: Int): Future[String] = {
    Http2Client.defaultH2Client.GET("https://www.google.com/") { resp =>
      resp.body.accumulate().map { bytes =>
        println(s"Finished response $tag")
        StandardCharsets.UTF_8.decode(bytes).toString
      }
    }
  }
}

object H2TwitterExample extends H2ClientExample(20, 30.seconds) {
  override def doCall(tag: Int): Future[Int] = callTwitter(tag).map(_.length)

  private[this] def callTwitter(tag: Int): Future[String] = {
    Http2Client.defaultH2Client.GET("https://twitter.com/") { resp =>
      resp.body.accumulate().map { bytes =>

        println(s"Finished response $tag of size ${bytes.remaining()}: ${resp.headers}")
        StandardCharsets.UTF_8.decode(bytes).toString
      }
    }
  }
}