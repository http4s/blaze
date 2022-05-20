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

class BenchParser(maxReq: Int = 1034, maxHeader: Int = 1024)
    extends Http1ServerParser(maxReq, maxHeader, 1) {
  def parseLine(s: ByteBuffer) = parseRequestLine(s)

  def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

  def parsecontent(s: ByteBuffer): ByteBuffer = parseContent(s)

  def badMessage(status: Int, reason: String): Unit =
    sys.error(s"Bad message: $status, $reason")

  def submitRequestLine(
      methodString: String,
      uri: String,
      scheme: String,
      majorversion: Int,
      minorversion: Int): Boolean = false

  def headerComplete(name: String, value: String) = false
}
