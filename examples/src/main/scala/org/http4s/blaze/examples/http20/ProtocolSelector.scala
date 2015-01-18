package org.http4s.blaze.examples.http20

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import org.http4s.blaze.examples.http20.Http2Server._
import org.http4s.blaze.http.http20._
import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.{TailStage, LeafBuilder}
import org.http4s.blaze.util.Execution._

import ALPNPipelineSelector._

object ProtocolSelector {
  def apply(engine: SSLEngine, service: HttpService, maxBody: Long, maxNonbodyLength: Int, ec: ExecutionContext): ALPNPipelineSelector = {
    
    def select(s: String): LeafBuilder[ByteBuffer] = s match {
    case HTTP2 => LeafBuilder(http2Stage(service, maxBody, maxNonbodyLength, ec))
    case _     => LeafBuilder(new HttpServerStage(maxBody, maxNonbodyLength)(service))
    }
    
    new ALPNPipelineSelector(engine, select)
  }

  private def http2Stage(service: HttpService, maxBody: Long, maxHeadersLength: Int, ec: ExecutionContext): TailStage[ByteBuffer] = {

    def newNode(streamId: Int): LeafBuilder[Http2Meg] = {
      LeafBuilder(new BasicHttpStage(streamId, maxBody, Duration.Inf, trampoline, service))
    }

    new Http2Stage[Headers](
      new TupleHeaderDecoder(maxHeadersLength),
      new TupleHeaderEncoder(),
      node_builder = newNode,
      timeout = Duration.Inf,
      maxInboundStreams = 300,
      ec = ec
    )
  }
} 

