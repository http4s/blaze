package org.http4s.blaze.http

import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.net.InetSocketAddress

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.util.{GenericSSLContext, BufferTools, Execution}


object HttpClient extends HttpClient {
  override lazy val connectionManager = new ClientChannelFactory()

  override protected val sslContext: SSLContext = GenericSSLContext.clientSSLContext()
}


trait HttpClient {

  protected def connectionManager: ClientChannelFactory

  protected def sslContext: SSLContext

  protected def runReq(method: String,
                          url: String,
                      headers: Seq[(String, String)],
                         body: ByteBuffer,
                      timeout: Duration)(implicit ec: ExecutionContext): Future[Response] = {

    val (host, port, scheme, uri) = parseURL(url)

    val head = connectionManager.connect(new InetSocketAddress(host, port))

    head.flatMap { head =>
      val t = new HttpClientStage()

      if (scheme == "https") {
        val eng = sslContext.createSSLEngine()
        eng.setUseClientMode(true)
        LeafBuilder(t).prepend(new SSLStage(eng)).base(head)
      }
      else LeafBuilder(t).base(head)

      head.sendInboundCommand(Command.Connected)
      val f = t.makeRequest(method, host, uri, headers, body)
      // Shutdown our connection
      f.onComplete( _ => t.sendOutboundCommand(Command.Disconnect))

      f
    }
  }

  def GET(url: String, headers: Seq[(String, String)] = Nil, timeout: Duration = Duration.Inf)
         (implicit ec: ExecutionContext = Execution.trampoline): Future[HttpResponse] = {
    val r = runReq("GET", url, headers, BufferTools.emptyBuffer, timeout)
    r.flatMap {
      case r: HttpResponse => Future.successful(r)
      case r => Future.failed(new Exception(s"Received invalid response type: ${r.getClass}"))
    }(Execution.directec)
  }
}
