package org.http4s.blaze.examples.spdy

import org.eclipse.jetty.npn.NextProtoNego
import org.log4s.getLogger
import java.util


class ServerProvider extends NextProtoNego.ServerProvider {
  private[this] val logger = getLogger

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
