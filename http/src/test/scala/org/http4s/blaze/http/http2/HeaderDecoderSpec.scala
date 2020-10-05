/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

class HeaderDecoderSpec extends Specification {
  private val testHeaders = Seq("foo" -> "bar")

  private val headersBlockSize = testHeaders.foldLeft(0) { case (acc, (k, v)) =>
    acc + 32 + k.length + v.length
  }

  "HeaderDecoder" should {
    "decode a full headers block" in {
      val bb = HeaderCodecHelpers.encodeHeaders(
        testHeaders,
        Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
      val dec =
        new HeaderDecoder(Int.MaxValue, false, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
      dec.decode(bb, -1, true) must_== Continue
      dec.finish() must_== testHeaders

      // Decode another block to make sure we don't contaminate the first
      val nextHs = (0 until 10).map(i => i.toString -> i.toString)
      val nextEncodedHs =
        HeaderCodecHelpers.encodeHeaders(nextHs, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

      dec.decode(nextEncodedHs, -1, true) must_== Continue
      dec.finish() must_== nextHs
    }

    "decode a header block in chunks" in {
      val bb = HeaderCodecHelpers.encodeHeaders(
        testHeaders,
        Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
      val dec =
        new HeaderDecoder(Int.MaxValue, false, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

      val b1 = BufferTools.takeSlice(bb, bb.remaining() / 2)

      dec.decode(b1, -1, false) must_== Continue
      dec.decode(bb, -1, true) must_== Continue
      dec.finish() must_== testHeaders
    }

    "count the current header block size" in {
      val bb = HeaderCodecHelpers.encodeHeaders(
        testHeaders,
        Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
      val dec =
        new HeaderDecoder(Int.MaxValue, false, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

      dec.currentHeaderBlockSize must_== 0
      dec.decode(bb, -1, true) must_== Continue
      dec.currentHeaderBlockSize must_== headersBlockSize
      dec.headerListSizeOverflow must beFalse

      dec.finish() must_== testHeaders
      dec.currentHeaderBlockSize must_== 0
    }

    "now overflow the maxHeaderBlockSize" in {
      val bb = HeaderCodecHelpers.encodeHeaders(
        testHeaders ++ testHeaders,
        Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
      val dec = new HeaderDecoder(
        headersBlockSize, /*discardOnOverflow*/ true,
        Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)

      dec.decode(bb, -1, true) must_== Continue
      dec.headerListSizeOverflow must beTrue
      dec.finish() must_== testHeaders // didn't get the second round
      dec.headerListSizeOverflow must beFalse
    }

    "decompression errors are connection errors" in {
      val bb = ByteBuffer.wrap(Array[Int](0x00, 0x85, 0xf2, 0xb2, 0x4a, 0x84, 0xff, 0x84, 0x49,
        0x50, 0x9f, 0xff).map(_.toByte))

      val dec =
        new HeaderDecoder(Int.MaxValue, true, Http2Settings.DefaultSettings.HEADER_TABLE_SIZE)
      dec.decode(bb, -1, true) must throwA[Http2SessionException].like {
        case Http2SessionException(code, _) =>
          code must_== Http2Exception.COMPRESSION_ERROR.code
      }
    }.pendingUntilFixed("The twitter HPACK decoder isn't fully spec compliant yet")
  }
}
