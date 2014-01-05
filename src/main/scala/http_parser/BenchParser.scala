package http_parser

import java.nio.ByteBuffer
import http_parser.RequestParser.State

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */


class BenchParser(maxReq: Int = 1034, maxHeader: Int = 1024) extends RequestParser(maxReq, maxHeader, 1) {

  def parseLine(s: ByteBuffer) = parseRequestLine(s)

  def state(state: State): Unit = super.setState(state)

  def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

  def parsecontent(s: ByteBuffer): Boolean = parseContent(s)

  def badMessage(status: Int, reason: String) {
    sys.error(s"Bad message: $status, $reason")
  }

  def earlyEOF() {}

  def startRequest(methodString: String,
                   uri: String,
                   scheme: String,
                   majorversion: Int,
                   minorversion: Int): Boolean = true

  def submitContent(buffer: ByteBuffer): Boolean = {
    buffer.position(buffer.limit())
    true
  }

  def headersComplete() {}

  def requestComplete() { }

  def headerComplete(name: String, value: String) { }
}
