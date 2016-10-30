package org.http4s.blaze.http.parser

import java.nio.ByteBuffer

import org.http4s.blaze.http.parser.BaseExceptions.BadCharacter
import org.specs2.mutable.Specification


class ParserBaseSpec extends Specification {

  class Base(isLenient: Boolean = false, limit: Int = 1024) extends ParserBase(10*1024, isLenient) {
    resetLimit(limit)
  }

  "ParserBase.next" should {
    "Provide the next valid char" in {
      val buffer = ByteBuffer.wrap("OK".getBytes)
        new Base().next(buffer, false) must_== 'O'.toByte
    }

    "Provide the EMPTY_BUFF token on empty ByteBuffer" in {
      val buffer = ByteBuffer.allocate(0)
      new Base().next(buffer, false) must_== HttpTokens.EMPTY_BUFF
    }

    "throw a BadCharacter if not lenient" in {
      val buffer = ByteBuffer.allocate(1)
      buffer.put(0x00.toByte)
      buffer.flip()

      new Base(isLenient = false).next(buffer, false) must throwA[BadCharacter]
    }

    "Provide a REPLACEMENT char on invalid character" in {
      val buffer = ByteBuffer.allocate(1)
      buffer.put(0x00.toByte)
      buffer.flip()

      new Base(isLenient = true).next(buffer, false) must_== HttpTokens.REPLACEMENT
    }
  }
}
