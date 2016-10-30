package org.http4s.blaze.http.parser

import java.nio.ByteBuffer


class BenchParser(maxReq: Int = 1034, maxHeader: Int = 1024) extends Http1ServerParser(maxReq, maxHeader, 1) {

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
                        minorversion: Int): Boolean = false

  def headerComplete(name: String, value: String) = false
}
