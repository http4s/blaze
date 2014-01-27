package blaze.examples.spdy

import org.eclipse.jetty.npn.NextProtoNego
import java.util
import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */
class ServerProvider extends NextProtoNego.ServerProvider with Logging {

  var selected: String = "http/1.1"

  def protocolSelected(protocol: String) {
    logger.info("Selected protocol: " + protocol)
    selected = protocol
  }

  def unsupported() {
    logger.error("Unsupported protocols")
  }

  def protocols(): util.List[String] = {
    import collection.JavaConversions._
    "spdy/3.1"::Nil
  }
}
