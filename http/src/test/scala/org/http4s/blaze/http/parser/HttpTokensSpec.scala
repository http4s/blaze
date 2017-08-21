package org.http4s.blaze.http.parser


import org.specs2.mutable._

import org.http4s.blaze.http.parser.BaseExceptions.BadMessage

class HttpTokensSpec extends Specification {

  val smalChrs = List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  val bigChrs = smalChrs.map(_.toTitleCase)

  "HttpTokens" should {
    "parse hex chars to ints" in {
      smalChrs.map(c => HttpTokens.hexCharToInt(c)) should_== (0 until 16 toList)

      bigChrs.map(c => HttpTokens.hexCharToInt(c)) should_== (0 until 16 toList)

      HttpTokens.hexCharToInt('x') should throwA[BadMessage]
    }
    
    "Identify hex chars" in {
      (0 until 256).map{ i =>
        HttpTokens.isHexChar(i.toByte) should_== (smalChrs.contains(i.toChar) || bigChrs.contains(i.toChar))
      }.reduce(_ and _)
    }

    "Identify whitespace" in {
      (0 until 256).map { i =>
        HttpTokens.isWhiteSpace(i.toChar) should_== (i.toChar == ' ' || i.toChar == '\t')
      }.reduce(_ and _)
    }
  }

}
