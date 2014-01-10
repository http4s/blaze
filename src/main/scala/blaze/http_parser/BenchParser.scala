package blaze
package http_parser

import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */


class BenchParser(maxReq: Int = 1034, maxHeader: Int = 1024) extends Http1Parser(maxReq, maxHeader, 1) {

  def parseLine(s: ByteBuffer) = parseRequestLine(s)

  def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

  def parsecontent(s: ByteBuffer): ByteBuffer = parseContent(s)

  def badMessage(status: Int, reason: String) {
    sys.error(s"Bad message: $status, $reason")
  }


  def submitRequestLine(methodString: String,
                   uri: String,
                   scheme: String,
                   majorversion: Int,
                   minorversion: Int): Unit = {}

  def headerComplete(name: String, value: String) { }
}
