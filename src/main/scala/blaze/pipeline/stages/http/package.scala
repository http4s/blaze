package blaze.pipeline.stages

import java.nio.ByteBuffer
import blaze.pipeline.TailStage
import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
package object http {

  sealed trait Response
  case class HttpResponse(status: String, code: Int, headers: Traversable[(String, String)], body: ByteBuffer) extends Response
  case class WSResponse(stage: TailStage[WebSocketFrame]) extends Response

}
