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
import org.http4s.blaze.testkit.BlazeTestSuite

class ParserBaseSuite extends BlazeTestSuite {
  private class Base(isLenient: Boolean = false, limit: Int = 1024)
      extends ParserBase(10 * 1024, isLenient) {
    resetLimit(limit)
  }

  test("A ParserBase.next should provide the next valid char") {
    val buffer = ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8))
    assertEquals(new Base().next(buffer, false), 'O')
  }

  test("A ParserBase.next should provide the EMPTY_BUFF token on empty ByteBuffer") {
    val buffer = ByteBuffer.allocate(0)
    assertEquals(new Base().next(buffer, false), HttpTokens.EMPTY_BUFF)
  }

  test("A ParserBase.next should throw a BadCharacter if not lenient") {
    val buffer = ByteBuffer.allocate(1)
    buffer.put(0x00.toByte)
    buffer.flip()

    intercept[BadCharacter](new Base(isLenient = false).next(buffer, false))
  }

  test("A ParserBase.next should provide a REPLACEMENT char on invalid character") {
    val buffer = ByteBuffer.allocate(1)
    buffer.put(0x00.toByte)
    buffer.flip()

    assertEquals(new Base(isLenient = true).next(buffer, false), HttpTokens.REPLACEMENT)
  }
}
