package org.http4s.blaze.examples.http20

import org.eclipse.jetty.alpn.ALPN
import org.log4s.getLogger
import java.util


class ServerProvider extends ALPN.ServerProvider {

  private[this] val logger = getLogger

  var selected: String = "http/1.1"

  override def select(protocols: util.List[String]): String = {
    logger.info("Selected protocols: " + protocols)
    selected = "h2-14"   // Firefox 34 advertises "h2-14" but its a lie
    selected
  }

  override def unsupported() {
    logger.error("Unsupported protocols")
  }
}
