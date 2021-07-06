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

import org.http4s.blaze.testkit.BlazeTestSuite

class HeaderNamesSuite extends BlazeTestSuite {
  test("A HeaderNames.validH2HeaderKey should match a valid header name") {
    assert(HeaderNames.validH2HeaderKey("abc"))
  }

  test("A HeaderNames.validH2HeaderKey should fail a zero length name") {
    assertEquals(HeaderNames.validH2HeaderKey(""), false)
  }

  test("A HeaderNames.validH2HeaderKey should fail a pseudo header") {
    assertEquals(HeaderNames.validH2HeaderKey(":method"), false)
  }

  test("A HeaderNames.validH2HeaderKey should fail a header with a capital letter") {
    assertEquals(HeaderNames.validH2HeaderKey("Date"), false)
  }

  test("A HeaderNames.validH2HeaderKey should fail a header with an invalid char") {
    assertEquals(HeaderNames.validH2HeaderKey("Date@"), false)
  }

  test("A HeaderNames.validH2HeaderKey should acccept a header with numbers") {
    assert(HeaderNames.validH2HeaderKey(('0' to '9').mkString))
  }

  test("A HeaderNames.validH2HeaderKey should accept a header with lower case letters") {
    assert(HeaderNames.validH2HeaderKey(('a' to 'z').mkString))
  }

  test("A HeaderNames.validH2HeaderKey should accept a header with non-delimiters") {
    assert(HeaderNames.validH2HeaderKey("!#$%&'*+-.^_`|~"))
  }
}
