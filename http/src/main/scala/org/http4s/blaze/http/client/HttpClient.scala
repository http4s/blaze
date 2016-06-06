package org.http4s.blaze.http.client

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.{Execution, GenericSSLContext}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

trait HttpClient {

  protected def getConnection(host: String, port: Int, scheme: String, timeout: Duration): Future[HttpClientStage]

  protected def returnConnection(stage: HttpClientStage): Unit

  /** Execute a request. This is the entry method for client usage */
  protected def runReq[A](method: String,
                          url: String,
                          headers: Seq[(String, String)],
                          body: ByteBuffer,
                          timeout: Duration)
                         (action: ClientResponse => Future[A])(implicit ec: ExecutionContext): Future[A] = {

    val (host, port, scheme, uri) = HttpClient.parseURL(url)

    getConnection(host, port, scheme, timeout).flatMap { stage =>

      val f = stage.makeRequest(method, host, uri, headers, body).flatMap(action)
      // Shutdown our connection
      f.onComplete( _ => returnConnection(stage))(Execution.directec)
      f
    }
  }
}

object HttpClient extends HttpClient with Actions {
  private lazy val connectionManager = new ClientChannelFactory()

  private val sslContext: SSLContext = GenericSSLContext.clientSSLContext()

  override protected def returnConnection(stage: HttpClientStage): Unit = {
    stage.sendOutboundCommand(Command.Disconnect)
  }

  override protected def getConnection(host: String, port: Int, scheme: String, timeout: Duration): Future[HttpClientStage] = {
    connectionManager.connect(new InetSocketAddress(host, port)).map { head =>
      val clientStage = new HttpClientStage(timeout)

      if (scheme == "https") {
        val eng = sslContext.createSSLEngine()
        eng.setUseClientMode(true)
        LeafBuilder(clientStage).prepend(new SSLStage(eng)).base(head)
      }
      else LeafBuilder(clientStage).base(head)

      head.sendInboundCommand(Command.Connected)

      clientStage
    }(Execution.directec)
  }

  // TODO: the robustness of this method to varying input is highly questionable
  private def parseURL(url: String): (String, Int, String, String) = {
    val uri = java.net.URI.create(if (url.startsWith("http")) url else "http://" + url)

    val port = if (uri.getPort > 0) uri.getPort else (if (uri.getScheme == "http") 80 else 443)

    (uri.getHost,
      port,
      uri.getScheme,
      if (uri.getQuery != null) uri.getPath + "?" + uri.getQuery else uri.getPath
      )
  }
}