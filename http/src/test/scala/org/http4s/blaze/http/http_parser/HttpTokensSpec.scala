package org.http4s.blaze.http.http_parser


import org.specs2.mutable._

import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest

/**
 * @author Bryce Anderson
 *         Created on 2/8/14
 */
class HttpTokensSpec extends Specification {

  val smalChrs = List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  val bigChrs = smalChrs.map(_.toTitleCase)

  "HttpTokens" should {
    "parse hex chars to ints" in {
      smalChrs.map(c => HttpTokens.hexCharToInt(c.toByte)) should_== (0 until 16 toList)

      bigChrs.map(c => HttpTokens.hexCharToInt(c.toByte)) should_== (0 until 16 toList)

      HttpTokens.hexCharToInt('x') should throwA[BadRequest]
    }
    
    "Identify hex chars" in {
      (0 until 256).map{ i =>
        HttpTokens.isHexChar(i.toByte) should_== (smalChrs.contains(i.toChar) || bigChrs.contains(i.toChar))
      }.reduce(_ and _)
    }

    "Identify whitespace" in {
      (0 until 256).map { i =>
        HttpTokens.isWhiteSpace(i.toByte) should_== (i.toChar == ' ' || i.toChar == '\t')
      }.reduce(_ and _)
    }
  }

}
