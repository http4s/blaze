package blaze.pipeline.stages

import java.nio.ByteBuffer
import blaze.pipeline.LeafBuilder
import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame

import java.nio.charset.StandardCharsets.UTF_8
import scala.xml.Node
import java.nio.charset.{StandardCharsets, Charset}

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
package object http {

  type Headers = Seq[(String, String)]

  sealed trait Response

  case class SimpleHttpResponse(status: String, code: Int, headers: Headers, body: ByteBuffer) extends Response {
    def stringBody(charset: Charset = StandardCharsets.UTF_8): String = {
      charset.decode(body.asReadOnlyBuffer()).toString
    }
  }

  object SimpleHttpResponse {
    def Ok(body: Array[Byte]): SimpleHttpResponse = SimpleHttpResponse("OK", 200, Nil, ByteBuffer.wrap(body))
    def Ok(body: String): SimpleHttpResponse = Ok(body.getBytes(UTF_8))
    def Ok(body: Node): SimpleHttpResponse = Ok(body.toString())
  }

  case class WSResponse(stage: LeafBuilder[WebSocketFrame]) extends Response
}
