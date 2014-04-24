package org.http4s.blaze.http.spdy

import org.http4s.blaze.util.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */

abstract class SpdyException(msg: String) extends Exception(msg)

class ProtocolException(msg: String) extends SpdyException(msg) with Logging {
  logger.info(msg, this)
}

