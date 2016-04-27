package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.http.websocket.WSStage
import org.http4s.websocket.WebsocketBits.WebSocketFrame

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration


object WsClient extends WsClient {
  protected def runReq(url: String, timeout: Duration, stage: WSStage)(implicit ec: ExecutionContext): Future[Unit] = {

    ???
  }
}

private trait WsClient {

}
