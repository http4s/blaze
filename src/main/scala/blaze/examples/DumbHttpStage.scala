package blaze
package examples

import java.nio.ByteBuffer

import scala.concurrent.Future
import blaze.pipeline.stages.http.{Response, HttpStage}
import blaze.http_parser.BaseExceptions.BadRequest

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class ExampleHttpStage(maxRequestLength: Int) extends HttpStage(maxRequestLength) {
  def handleRequest(method: String, uri: String, headers: Traversable[(String, String)], body: ByteBuffer): Future[Response] = {

    if (uri.endsWith("error")) Future.failed(new BadRequest("You requested an error!"))
    else {
      val body = ByteBuffer.wrap(s"Hello world!\nRequest URI: $uri".getBytes())
      Future.successful(Response("OK", 200, Nil, body))
    }
  }
}
