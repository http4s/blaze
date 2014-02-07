package blaze.util

import blaze.channel.nio2.ClientChannelFactory
import blaze.pipeline.{Command, LeafBuilder}
import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.net.InetSocketAddress
import blaze.pipeline.stages.http.{HttpClientStage, Response}

/**
 * @author Bryce Anderson
 *         Created on 2/6/14
 */
object HttpClient {


  private lazy val connManager = new ClientChannelFactory()

  // TODO: perhaps we should really parse the URL
  private def parseURL(url: String): (String, Int, String, String) = {
    //("www.google.com", 80, "http", "/")
    ???
  }

  private def runReq(method: String,
                     url: String,
                     headers: Seq[(String, String)],
                     body: ByteBuffer,
                     timeout: Duration)(implicit ec: ExecutionContext): Future[Response] = {

    val (host, port, scheme, uri) = parseURL(url)

    val fhead = connManager.connect(new InetSocketAddress(host, port))

    fhead.flatMap { head =>
      val t = new HttpClientStage()
      LeafBuilder(t).base(head)
      head.sendInboundCommand(Command.Connect)
      val f = t.makeRequest(method, host, uri, headers, body)
      // Shutdown our connection
      f.onComplete( _ => t.sendOutboundCommand(Command.Disconnect))

      f
    }
  }

  def GET(url: String, headers: Seq[(String, String)], timeout: Duration = Duration.Inf)
         (implicit ec: ExecutionContext = Execution.trampoline): Future[Response] = {
    runReq("GET", url, headers, BufferTools.emptyBuffer, timeout)
  }
}
