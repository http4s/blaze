package org.http4s.blaze.http.http_parser

import org.scalatest.{Matchers, WordSpec}
import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest

/**
 * @author Bryce Anderson
 *         Created on 2/8/14
 */
class HttpTokensSpec extends WordSpec with Matchers {

  val smalChrs = List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  val bigChrs = smalChrs.map(_.toTitleCase)

  "HttpTokens" should {
    "parse hex chars to ints" in {
      smalChrs.map(c => HttpTokens.hexCharToInt(c.toByte)) should equal (0 until 16 toList)

      bigChrs.map(c => HttpTokens.hexCharToInt(c.toByte)) should equal (0 until 16 toList)

      a [BadRequest] should be thrownBy HttpTokens.hexCharToInt('x')
    }
    
    "Identify hex chars" in {
      0 until 256 foreach { i =>
        HttpTokens.isHexChar(i.toByte) should equal (smalChrs.contains(i.toChar) || bigChrs.contains(i.toChar))
      }
    }

    "Identify whitespace" in {
      0 until 256 foreach { i =>
        HttpTokens.isWhiteSpace(i.toByte) should equal (i.toChar == ' ' || i.toChar == '\t')
      }
    }
  }

}
