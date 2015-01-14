package org.http4s.blaze.examples.http20

import java.nio.ByteBuffer
import java.util
import javax.net.ssl.SSLEngine

import org.eclipse.jetty.alpn.ALPN
import org.http4s.blaze.pipeline.Command.{EOF, Error}
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution.trampoline

import scala.util.{Failure, Success}

class ALPNPipelineSelector(engine: SSLEngine, builder: String => LeafBuilder[ByteBuffer]) extends TailStage[ByteBuffer] {
  import ALPNPipelineSelector._

  ALPN.put(engine, new ServerProvider)

  private var selected: String = HTTP1

  override def name: String = "PipelineSelector"

  override protected def stageStartup(): Unit = {
    channelWrite(Nil).onComplete {
      case Success(_)   => selectPipeline()
      case Failure(EOF) => // NOOP
      case Failure(t)   =>
        logger.error(t)(s"$name failed to startup")
        sendOutboundCommand(Error(t))
    }(trampoline)
  }

  private def selectPipeline(): Unit = {
    try {
      val provider = ALPN.get(engine)
      val b = builder(selected)
      this.replaceInline(b, true)
    } catch {
      case t: Throwable =>
        logger.error(t)("Failure building pipeline")
        sendOutboundCommand(Error(t))
    }
  }

  private class ServerProvider extends ALPN.ServerProvider {
    import scala.collection.JavaConversions._

    override def select(protocols: util.List[String]): String = {
      logger.info("Available protocols: " + protocols)
      if (protocols.exists(_ == HTTP2)) {
        selected = "h2-14"
      }
      selected
    }

    override def unsupported() {
      logger.info(s"Unsupported protocols, defaulting to $HTTP1")
    }
  }

}

object ALPNPipelineSelector {
  val HTTP1 = "http/1.1"
  val HTTP2 = "h2-14"
}
