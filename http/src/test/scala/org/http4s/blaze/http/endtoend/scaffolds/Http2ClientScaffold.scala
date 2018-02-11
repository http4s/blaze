package org.http4s.blaze.http.endtoend.scaffolds

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DefaultFlowStrategy, Http2Settings}
import org.http4s.blaze.http._
import org.http4s.blaze.http.endtoend.scaffolds.ClientScaffold.Response
import org.http4s.blaze.http.http2.client.{ClientPriorKnowledgeHandshaker, Http2ClientSessionManagerImpl}
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.Execution

import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

private[endtoend] class Http2ClientScaffold extends ClientScaffold(2, 0) {

  private[this] implicit val ec = Execution.trampoline

  private val underlying = {
    val h2Settings = Http2Settings.default.copy(
      initialWindowSize = 256 * 1024)

    val manager = new Http2ClientSessionManagerImpl(
      HttpClientConfig.Default,
      h2Settings,
      new mutable.HashMap[String, Future[Http2ClientSession]]
    ) {
      override protected def initialPipeline(head: HeadStage[ByteBuffer]): Future[Http2ClientSession] = {
        val p = Promise[Http2ClientSession]

        // TODO: we need a better model for these
        val localSettings = h2Settings.copy()

        val flowStrategy = new DefaultFlowStrategy(localSettings)
        val handshaker = new ClientPriorKnowledgeHandshaker(localSettings, flowStrategy, Execution.trampoline)
        p.completeWith(handshaker.clientSession)
        LeafBuilder(handshaker).base(head)

        head.sendInboundCommand(Command.Connected)
        p.future
      }
    }
    new HttpClientImpl(manager)
  }

  override def close(): Unit = {
    underlying.close()
    ()
  }

  // Not afraid to block: this is for testing.
  override def runRequest(request: HttpRequest): Response = {
    val fResponse = underlying(request) { resp =>
      val prelude = HttpResponsePrelude(resp.code, resp.status, resp.headers)
      resp.body.accumulate().map { body =>
        val arr = new Array[Byte](body.remaining)
        body.get(arr)
        Response(prelude, arr)
      }
    }

    Await.result(fResponse, 10.seconds)
  }
}
