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

package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.http2.mocks.MockHeadStage
import org.http4s.blaze.http.{BodyReader, HttpRequest}
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.util.{Failure, Success, Try}

class ClientStageSpec extends Specification {
  import PseudoHeaders._

  private val request = HttpRequest(
    method = "GET",
    url = "https://foo.com/Foo?Bar",
    majorVersion = 2,
    minorVersion = 0,
    headers = Seq("Not-Lower-Case" -> "Value"),
    body = BodyReader.EmptyBodyReader
  )

  private val resp = HeadersFrame(
    priority = Priority.NoPriority,
    endStream = true,
    headers = Seq(Status -> "200")
  )

  "ClientStage" >> {
    "make appropriate pseudo headers" >> {
      ClientStage.makeHeaders(request) must_== Try(
        Vector(
          Method -> "GET",
          Scheme -> "https",
          Authority -> "foo.com",
          Path -> "/Foo?Bar",
          "not-lower-case" -> "Value"
        ))
    }

    "Mark the first HEADERS frame as EOS if there isn't a body" >> {
      val cs = new ClientStage(request)
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)

      val (HeadersFrame(_, eos, _), _) = head.writes.dequeue()
      eos must beTrue
    }

    "Not mark the first HEADERS frame EOS if there is a body" >> {
      val body = BodyReader.singleBuffer(ByteBuffer.allocate(1))
      val cs = new ClientStage(request.copy(body = body))
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)

      val (HeadersFrame(_, eos, _), _) = head.writes.dequeue()
      eos must beFalse
    }

    "Write a body to the pipeline and mark the last frame EOS" >> {
      val d = ByteBuffer.allocate(1)
      val body = BodyReader.singleBuffer(d)
      val cs = new ClientStage(request.copy(body = body))
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)

      head.consumeOutboundData().map(_.endStream) must_== (Seq(false))
      val Seq(DataFrame(eos, data)) = head.consumeOutboundData()
      eos must beTrue
      data must_== d // should be referentially equal
    }

    "Response will have an empty body if the first inbound frame is marked EOS" >> {
      val cs = new ClientStage(request)
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)

      head.consumeOutboundData().map(_.endStream) must_== (Seq(true))
      head.reads.dequeue().success(resp)

      // available since all these tests execute single threaded
      val Some(Success(response)) = cs.result.value
      response.body.isExhausted must beTrue
    }

    "Responses with a body can have the body available through the reader" >> {
      val cs = new ClientStage(request)
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)

      head.consumeOutboundData().map(_.endStream) must_== (Seq(true))
      head.reads.dequeue().success(resp.copy(endStream = false))

      // available since all these tests execute single threaded
      val Some(Success(response)) = cs.result.value
      response.body.isExhausted must beFalse

      val f = response.body()
      f.isCompleted must beFalse

      val d = BufferTools.allocate(10)
      head.reads.dequeue().success(DataFrame(true, d))

      val Some(Success(data)) = f.value
      data must_== d
      response.body.isExhausted must beTrue
    }

    "Releasing the response will send a disconnect message" >> {
      val cs = new ClientStage(request)
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)
      head.consumeOutboundData().map(_.endStream) must_== (Seq(true))
      head.reads.dequeue().success(resp)

      val Some(Success(r)) = cs.result.value
      r.release()

      head.disconnected must beTrue
    }

    "Failure of writing the request prelude results in a disconnect" >> {
      val cs = new ClientStage(request)
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)
      val (_, writeP) = head.writes.dequeue()
      writeP.failure(Command.EOF)

      head.disconnected must beTrue
      cs.result.value must beLike { case Some(Failure(Command.EOF)) =>
        ok
      }
    }

    "Failure to read the response results in a disconnect" >> {
      val cs = new ClientStage(request)
      val head = new MockHeadStage[StreamFrame]
      LeafBuilder(cs).base(head)

      head.sendInboundCommand(Command.Connected)
      head.consumeOutboundData().map(_.endStream) must_== (Seq(true))
      head.reads.dequeue().failure(Command.EOF)

      head.disconnected must beTrue
      cs.result.value must beLike { case Some(Failure(Command.EOF)) =>
        ok
      }
    }
  }
}
