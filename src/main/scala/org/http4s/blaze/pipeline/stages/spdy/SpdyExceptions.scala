package org.http4s.blaze.pipeline.stages.spdy

import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */

abstract class SpdyException(msg: String) extends Exception(msg)

class ProtocolException(msg: String) extends SpdyException(msg) with Logging {
  logger.info(msg, this)
}

