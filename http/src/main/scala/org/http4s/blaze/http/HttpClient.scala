package org.http4s.blaze.http

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.net.InetSocketAddress
import org.http4s.blaze.pipeline.stages.http.{SimpleHttpResponse, HttpClientStage, Response}
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import java.security.KeyStore
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.util.{BufferTools, Execution, BogusKeystore}

/**
 * @author Bryce Anderson
 *         Created on 2/6/14
 */
trait HttpClient {

  private lazy val connManager = new ClientChannelFactory()

  // TODO: is there a better way to make a dummy context? Clients shouldn't need a certificate I would think
  private lazy val sslContext = {
    val ksStream = BogusKeystore.asInputStream()
    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, BogusKeystore.getCertificatePassword)

    val context = SSLContext.getInstance("SSL")

    context.init(kmf.getKeyManagers(), null, null)
    context
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

  protected def runReq(method: String,
                     url: String,
                     headers: Seq[(String, String)],
                     body: ByteBuffer,
                     timeout: Duration)(implicit ec: ExecutionContext): Future[Response] = {

    val (host, port, scheme, uri) = parseURL(url)

    val fhead = connManager.connect(new InetSocketAddress(host, port))

    fhead.flatMap { head =>
      val t = new HttpClientStage()

      if (scheme == "https") {
        val eng = sslContext.createSSLEngine()
        eng.setUseClientMode(true)
        LeafBuilder(t).prepend(new SSLStage(eng)).base(head)
      }
      else LeafBuilder(t).base(head)

      head.sendInboundCommand(Command.Connect)
      val f = t.makeRequest(method, host, uri, headers, body)
      // Shutdown our connection
      f.onComplete( _ => t.sendOutboundCommand(Command.Disconnect))

      f
    }
  }

  def GET(url: String, headers: Seq[(String, String)] = Nil, timeout: Duration = Duration.Inf)
         (implicit ec: ExecutionContext = Execution.trampoline): Future[SimpleHttpResponse] = {
    val r = runReq("GET", url, headers, BufferTools.emptyBuffer, timeout)
    r.flatMap {
      case r: SimpleHttpResponse => Future.successful(r)
      case r => Future.failed(new Exception(s"Received invalid response type: ${r.getClass}"))
    }(Execution.directec)
  }
}

object HttpClient extends HttpClient
