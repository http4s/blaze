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

package org.http4s.blaze.http

import org.specs2.mutable.Specification

class HeaderNamesSpec extends Specification {
  "HeaderNames.validH2HeaderKey" should {
    "Match a valid header name" in {
      HeaderNames.validH2HeaderKey("abc") must_== true
    }

    "Fail a zero length name" in {
      HeaderNames.validH2HeaderKey("") must_== false
    }

    "Fail a pseudo header" in {
      HeaderNames.validH2HeaderKey(":method") must_== false
    }

    "Fail a header with a capital letter" in {
      HeaderNames.validH2HeaderKey("Date") must_== false
    }

    "Fail a header with an invalid char" in {
      HeaderNames.validH2HeaderKey("Date@") must_== false
    }

    "Accept a header with numbers" in {
      HeaderNames.validH2HeaderKey('0' to '9' mkString) must_== true
    }

    "Accept a header with lower case letters" in {
      HeaderNames.validH2HeaderKey('a' to 'z' mkString) must_== true
    }

    "Accept a header with non-delimiters" in {
      HeaderNames.validH2HeaderKey("!#$%&'*+-.^_`|~") must_== true
    }
  }
}
