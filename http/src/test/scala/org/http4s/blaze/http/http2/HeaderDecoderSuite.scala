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

package org.http4s.blaze.http.http2

import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

class HeaderDecoderSuite extends BlazeTestSuite {
  private val testHeaders = Seq("foo" -> "bar")

  private val headersBlockSize = testHeaders.foldLeft(0) { case (acc, (k, v)) =>
    acc + 32 + k.length + v.length
  }

  test("A HeaderDecoder should decode a full headers block") {
    val bb =
      HeaderCodecHelpers.encodeHeaders(testHeaders, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
    val dec =
      new HeaderDecoder(Int.MaxValue, false, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
    assertEquals(dec.decode(bb, -1, true), Continue)
    assertEquals(dec.finish(), testHeaders)

    // Decode another block to make sure we don't contaminate the first
    val nextHs = (0 until 10).map(i => i.toString -> i.toString)
    val nextEncodedHs =
      HeaderCodecHelpers.encodeHeaders(nextHs, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

    assertEquals(dec.decode(nextEncodedHs, -1, true), Continue)
    assertEquals(dec.finish(), nextHs)
  }

  test("A HeaderDecoder should decode a header block in chunks") {
    val bb =
      HeaderCodecHelpers.encodeHeaders(testHeaders, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
    val dec =
      new HeaderDecoder(Int.MaxValue, false, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

    val b1 = BufferTools.takeSlice(bb, bb.remaining() / 2)

    assertEquals(dec.decode(b1, -1, false), Continue)
    assertEquals(dec.decode(bb, -1, true), Continue)
    assertEquals(dec.finish(), testHeaders)
  }

  test("A HeaderDecoder should count the current header block size") {
    val bb =
      HeaderCodecHelpers.encodeHeaders(testHeaders, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
    val dec =
      new HeaderDecoder(Int.MaxValue, false, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

    assertEquals(dec.currentHeaderBlockSize, 0)
    assertEquals(dec.decode(bb, -1, true), Continue)
    assertEquals(dec.currentHeaderBlockSize, headersBlockSize)
    assertEquals(dec.headerListSizeOverflow, false)

    assertEquals(dec.finish(), testHeaders)
    assertEquals(dec.currentHeaderBlockSize, 0)
  }

  test("A HeaderDecoder should now overflow the maxHeaderBlockSize") {
    val bb = HeaderCodecHelpers.encodeHeaders(
      testHeaders ++ testHeaders,
      Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
    val dec = new HeaderDecoder(
      headersBlockSize, /*discardOnOverflow*/ true,
      Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

    assertEquals(dec.decode(bb, -1, true), Continue)
    assert(dec.headerListSizeOverflow)
    assertEquals(dec.finish(), testHeaders) // didn't get the second round
    assertEquals(dec.headerListSizeOverflow, false)
  }

  // The twitter HPACK decoder isn't fully spec compliant yet
  /*test("A HeaderDecoder should decompression errors are connection errors") {
    val bb = ByteBuffer.wrap(
      Array[Int](0x00, 0x85, 0xf2, 0xb2, 0x4a, 0x84, 0xff, 0x84, 0x49, 0x50, 0x9f, 0xff).map(
        _.toByte))

    val dec =
      new HeaderDecoder(Int.MaxValue, true, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

    intercept[Http2SessionException](dec.decode(bb, -1, true))
  }*/
}
