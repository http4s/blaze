package blaze.pipeline.stages.spdy

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */

abstract class SpdyException(msg: String) extends Exception(msg)

class ProtocolException(msg: String) extends SpdyException(msg)

