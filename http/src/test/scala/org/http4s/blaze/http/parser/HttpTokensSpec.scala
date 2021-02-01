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

import org.specs2.mutable._

import org.http4s.blaze.http.parser.BaseExceptions.BadMessage

class HttpTokensSpec extends Specification {
  val smalChrs =
    List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  val bigChrs = smalChrs.map(_.toTitleCase)

  "HttpTokens" should {
    "parse hex chars to ints" in {
      smalChrs.map(c => HttpTokens.hexCharToInt(c)) should_== (0 until 16 toList)

      bigChrs.map(c => HttpTokens.hexCharToInt(c)) should_== (0 until 16 toList)

      HttpTokens.hexCharToInt('x') should throwA[BadMessage]
    }

    "Identify hex chars" in {
      (0 until 256)
        .map { i =>
          HttpTokens.isHexChar(i.toByte) should_== (smalChrs.contains(i.toChar) || bigChrs.contains(
            i.toChar))
        }
        .reduce(_.and(_))
    }

    "Identify whitespace" in {
      (0 until 256)
        .map { i =>
          HttpTokens.isWhiteSpace(i.toChar) should_== (i.toChar == ' ' || i.toChar == '\t')
        }
        .reduce(_.and(_))
    }
  }
}
