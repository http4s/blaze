/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.parser

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.parser.BaseExceptions.BadCharacter
import org.specs2.mutable.Specification

class ParserBaseSpec extends Specification {
  class Base(isLenient: Boolean = false, limit: Int = 1024)
      extends ParserBase(10 * 1024, isLenient) {
    resetLimit(limit)
  }

  "ParserBase.next" should {
    "Provide the next valid char" in {
      val buffer = ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8))
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
