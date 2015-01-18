package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.Command.OutboundCommand

import scala.concurrent.Future


private[http20]trait Http2Stage[T] {
  private type Http2Msg = NodeMsg.Http2Msg[T]

  /** Write the buffers to the socket */
  def writeBuffers(data: Seq[ByteBuffer]): Future[Unit]

  def streamRead(stream: AbstractStream[T]): Future[Http2Msg]

  def streamWrite(stream: AbstractStream[T], data: Seq[Http2Msg]): Future[Unit]

  def streamCommand(stream: AbstractStream[T], cmd: OutboundCommand): Unit

  def onFailure(t: Throwable, position: String): Unit

  def shutdownConnection(): Unit
}
