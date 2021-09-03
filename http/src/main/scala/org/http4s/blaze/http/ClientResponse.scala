/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.control.NonFatal

/** HTTP response received by the client
  *
  * @param code
  *   Response code
  * @param status
  *   Response message. This have no meaning for the HTTP connection, its just for human enjoyment.
  * @param headers
  *   Response headers
  * @param body
  *   [[BodyReader]] used to consume the response body.
  */
case class ClientResponse(code: Int, status: String, headers: Headers, body: BodyReader)

object ClientResponse {
  private val charsetRegex = "(?<=charset=)[^;]*".r

  def stringBody(response: ClientResponse): Future[String] = {
    val acc = new ArrayBuffer[ByteBuffer](8)

    var count = 0

    def go(): Future[String] =
      response
        .body()
        .flatMap { buffer =>
          count += buffer.remaining()
          if (buffer.hasRemaining) {
            acc += buffer
            go()
          } else {
            val b = BufferTools.joinBuffers(acc)
            val encoding = getCharset(response.headers)

            try {
              val bodyString = Charset.forName(encoding).decode(b).toString()
              Future.successful(bodyString)
            } catch { case NonFatal(t) => Future.failed(t) }
          }
        }(Execution.trampoline)

    go()
  }

  private def getCharset(hs: Headers): String =
    hs.collectFirst {
      case (k, v) if k.equalsIgnoreCase("content-type") =>
        charsetRegex.findFirstIn(v)
    }.flatten
      .getOrElse("UTF-8")
}
