package org.http4s.blaze.examples

import org.http4s.blaze.util.HttpClient
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * @author Bryce Anderson
 *         Created on 3/16/14
 */
object SSLClientTest {

  def main(args: Array[String]) {
    val f = HttpClient.GET("https://www.google.com/")

    val r = Await.result(f, 10.seconds)

    println(r)
    println(r.stringBody())
  }

}
