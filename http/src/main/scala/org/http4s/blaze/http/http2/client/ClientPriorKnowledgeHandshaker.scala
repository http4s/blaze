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

package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.Http2ClientSession
import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.pipeline.stages.{BasicTail, OneMessageStage}
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.BufferTools

import scala.concurrent.{ExecutionContext, Future, Promise}

/** Stage capable of performing the client HTTP2 handshake
  * and returning a `ClientSession` which is ready to dispatch
  * requests.
  *
  * The handshake goes like this:
  * - Send the handshake preface
  * - Send the initial settings frame
  * - Receive the initial settings frame
  * - Ack the initial settings frame
  * - Finish constructing the connection
  *
  * @param localSettings settings to transmit to the server while performing the handshake
  */
private[http] class ClientPriorKnowledgeHandshaker(
    localSettings: ImmutableHttp2Settings,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
    extends PriorKnowledgeHandshaker[Http2ClientSession](localSettings) {
  private[this] val session = Promise[Http2ClientSession]()

  def clientSession: Future[Http2ClientSession] = session.future

  override protected def stageStartup(): Unit = {
    logger.debug("initiating handshake")
    session.completeWith(handshake())
    ()
  }

  override protected def handlePreface(): Future[ByteBuffer] =
    channelWrite(bits.getPrefaceBuffer()).map(_ => BufferTools.emptyBuffer)

  override protected def handshakeComplete(
      remoteSettings: MutableHttp2Settings,
      data: ByteBuffer
  ): Future[Http2ClientSession] = {
    val tailStage = new BasicTail[ByteBuffer]("http2cClientTail")
    var newTail = LeafBuilder(tailStage)
    if (data.hasRemaining)
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](data))

    this.replaceTail(newTail, true)

    val h2ClientStage =
      new ClientSessionImpl(
        tailStage,
        localSettings,
        remoteSettings,
        flowStrategy,
        executor
      )

    Future.successful(h2ClientStage)
  }
}

private[client] object ClientPriorKnowledgeHandshaker {
  val DefaultClientSettings: Seq[Setting] = Vector(
    Http2Settings.ENABLE_PUSH(0) /*false*/
  )
}
