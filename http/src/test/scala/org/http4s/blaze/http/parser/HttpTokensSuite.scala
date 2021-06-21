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

import org.http4s.blaze.http.parser.BaseExceptions.BadMessage
import org.http4s.blaze.testkit.BlazeTestSuite

class HttpTokensSuite extends BlazeTestSuite {
  private val smalChrs =
    List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  private val bigChrs = smalChrs.map(_.toTitleCase)

  test("An HttpTokens should parse hex chars to ints") {

    assertEquals(smalChrs.map(c => HttpTokens.hexCharToInt(c)), (0 until 16).toList)

    assertEquals(bigChrs.map(c => HttpTokens.hexCharToInt(c)), (0 until 16).toList)

    intercept[BadMessage](HttpTokens.hexCharToInt('x'))
  }

  test("An HttpTokens should identify hex chars") {
    assert(
      (0 until 256)
        .forall { i =>
          HttpTokens.isHexChar(i.toByte) == (smalChrs.contains(i.toChar) || bigChrs.contains(
            i.toChar))
        }
    )
  }

  test("An HttpTokens should identify whitespace") {
    assert(
      (0 until 256)
        .forall { i =>
          HttpTokens.isWhiteSpace(i.toChar) == (i.toChar == ' ' || i.toChar == '\t')
        }
    )
  }
}
