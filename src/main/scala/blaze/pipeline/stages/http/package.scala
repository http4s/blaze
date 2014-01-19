package blaze.pipeline.stages

import java.nio.ByteBuffer
import blaze.pipeline.TailStage
import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame

import java.nio.charset.StandardCharsets.UTF_8
import scala.xml.Node

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
package object http {

  type Headers = Seq[(String, String)]

  sealed trait Response
  case class HttpResponse(status: String, code: Int, headers: Headers, body: ByteBuffer) extends Response {
    def withHeaders(h: Seq[(String, String)]) = copy(headers = h)
  }

  object HttpResponse {
    def Ok(body: Array[Byte]): HttpResponse = HttpResponse("OK", 200, Nil, ByteBuffer.wrap(body))
    def Ok(body: String): HttpResponse = Ok(body.getBytes(UTF_8))
    def Ok(body: Node): HttpResponse = Ok(body.toString())
  }

  case class WSResponse(stage: TailStage[WebSocketFrame]) extends Response

}
