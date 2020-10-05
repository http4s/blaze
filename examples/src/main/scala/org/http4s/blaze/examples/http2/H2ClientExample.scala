/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.examples.http2

import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2.client.Http2Client

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/** Examples which calls the Twitter or Google home pages using HTTP/2
  *
  * @note the Jetty ALPN boot library must have been loaded in order for
  *       ALPN negotiation to happen. See the Jetty docs at
  *       https://www.eclipse.org/jetty/documentation/9.3.x/alpn-chapter.html
  *       for more information.
  */
object H2ClientTwitterExample extends H2ClientExample(20, 30.seconds)

object H2ClientGoogleExample extends H2ClientExample(20, 30.seconds)

abstract class H2ClientExample(count: Int, timeout: Duration) {
  protected implicit val ec = scala.concurrent.ExecutionContext.global

  private[this] def doCall(tag: Int): Future[Int] =
    doCallString(tag).map(_.length)

  private[this] def doCallString(tag: Int): Future[String] =
    Http2Client.defaultH2Client.GET("https://www.google.com/") { resp =>
      resp.body.accumulate().map { bytes =>
        println(s"Finished response $tag of bytes ${bytes.remaining}: ${resp.headers}")
        StandardCharsets.UTF_8.decode(bytes).toString
      }
    }

  def main(args: Array[String]): Unit = {
    println(s"${getClass.getSimpleName} performing $count requests")

    Await.result(doCall(0), 5.seconds)

    // call the specified number of times
    def repeatCall(i: Int): Seq[Future[(Int, Int)]] = (0 until i).map(i => doCall(i).map(i -> _))

    Await.result(Future.sequence(repeatCall(count / 5)), timeout)
    val start = System.currentTimeMillis
    val resps = Await.result(Future.sequence(repeatCall(count)), timeout)
    val duration = System.currentTimeMillis - start

    val length = resps.foldLeft(0) { case (acc, (_, len)) =>
      acc + len
    }

    println(s"The total body length of ${resps.length} messages: $length. Took $duration millis")
  }
}
