package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine

import org.http4s.blaze.http._
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

object Http2Selector {
  def apply(engine: SSLEngine, 
           service: HttpService, 
           maxBody: Long, 
  maxNonbodyLength: Int, 
                ec: ExecutionContext): ALPNSelector = {

    val HTTP_1_1 = "http/1.1"
    val H2       = "h2"
    val H2_14    = "h2-14"
    
    def builder(s: String): LeafBuilder[ByteBuffer] = s match {
    case H2 | H2_14 => LeafBuilder(http2Stage(service, maxBody, maxNonbodyLength, ec))
    case _          => LeafBuilder(new HttpServerStage(maxBody, maxNonbodyLength, ec)(service))
    }

    def selector(protocols: Seq[String]): String =
      protocols.find {
        case H2    => true
        case H2_14 => true
        case _     => false
      } getOrElse(HTTP_1_1)
    
    new ALPNSelector(engine, selector, builder)
  }

  private def http2Stage(service: HttpService, maxBody: Long, maxHeadersLength: Int, ec: ExecutionContext): TailStage[ByteBuffer] = {

    def newNode(streamId: Int): LeafBuilder[Http2Msg] = {
      LeafBuilder(???) //new BasicHttpStage(streamId, maxBody, Duration.Inf, trampoline, service))
    }

    Http2Stage(
      nodeBuilder = newNode,
      timeout = Duration.Inf,
      ec = ec,
      maxHeadersLength = maxHeadersLength
    )
  }
}
