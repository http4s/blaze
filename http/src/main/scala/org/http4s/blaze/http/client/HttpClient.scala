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

  protected def closeConnection(stage: HttpClientStage): Unit

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

/** The default client is 'connection per request' */
object HttpClient extends HttpClient with ClientActions {
  private lazy val connectionManager = new ClientChannelFactory()

  private val sslContext: SSLContext = GenericSSLContext.clientSSLContext()

  override protected def returnConnection(stage: HttpClientStage): Unit = closeConnection(stage)

  override protected def closeConnection(stage: HttpClientStage): Unit = {
    stage.sendOutboundCommand(Command.Disconnect)
  }

  override protected def getConnection(host: String, port: Int, scheme: String, timeout: Duration): Future[HttpClientStage] = {
    connectionManager.connect(new InetSocketAddress(host, port)).map { head =>
      val clientStage = new HttpClientStage(timeout)

      if (scheme.equalsIgnoreCase("https")) {
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
    val uri = java.net.URI.create(if (isPrefixedWithHTTP(url)) url else "http://" + url)

    val port = if (uri.getPort > 0) uri.getPort else (if (uri.getScheme.equalsIgnoreCase("http")) 80 else 443)

    (uri.getHost,
      port,
      uri.getScheme,
      if (uri.getQuery != null) uri.getPath + "?" + uri.getQuery else uri.getPath
      )
  }

  private def isPrefixedWithHTTP(string: String): Boolean = {
    string.length >= 4 &&
      (string.charAt(0) == 'h' || string.charAt(0) == 'H') &&
      (string.charAt(1) == 't' || string.charAt(1) == 'T') &&
      (string.charAt(2) == 't' || string.charAt(2) == 'T') &&
      (string.charAt(3) == 'p' || string.charAt(3) == 'P')
  }
}