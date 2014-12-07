package org.http4s.blaze.examples.spdy

import org.eclipse.jetty.alpn.ALPN
import org.log4s.getLogger
import java.util


class ServerProvider extends ALPN.ServerProvider {

  private[this] val logger = getLogger

  var selected: String = "http/1.1"

  override def select(protocols: util.List[String]): String = {
    logger.info("Selected protocols: " + protocols)
    selected = "spdy/3.1"
    selected
  }

  override def unsupported() {
    logger.error("Unsupported protocols")
  }
}
