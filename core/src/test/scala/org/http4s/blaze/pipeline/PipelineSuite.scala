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

package org.http4s.blaze.pipeline

import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.{Execution, FutureUnit}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

class PipelineSuite extends BlazeTestSuite {
  private implicit def ec: ExecutionContext = Execution.trampoline

  class IntHead extends HeadStage[Int] {
    def name = "IntHead"

    override protected def doClosePipeline(cause: Option[Throwable]): Unit = ()

    @volatile
    var lastWrittenInt: Int = 0
    def writeRequest(data: Int): Future[Unit] = {
      lastWrittenInt = data
      FutureUnit
    }
    def readRequest(size: Int): Future[Int] = Future.successful(54)
  }

  class IntToString extends MidStage[Int, String] {
    def name = "IntToString"
    def readRequest(size: Int): Future[String] = channelRead(1).map(_.toString)
    def writeRequest(data: String): Future[Unit] =
      try channelWrite(data.toInt)
      catch { case t: NumberFormatException => Future.failed(t) }
  }

  class Noop[T] extends MidStage[T, T] {
    def name: String = "NOOP"
    def readRequest(size: Int): Future[T] = channelRead(size)
    def writeRequest(data: T): Future[Unit] = channelWrite(data)
  }

  class StringEnd extends TailStage[String] {
    def name: String = "StringEnd"

    var lastString = ""
  }

  test("A Pipeline should make a basic org.http4s.blaze.pipeline") {
    val head = new IntHead
    val tail = new StringEnd

    TrunkBuilder(new IntToString).cap(tail).base(head)

    val channelReadResult = tail.channelRead()

    tail.channelWrite("32").map(_ => head)

    for {
      _ <- assertFuture(channelReadResult, "54")
      _ <- assertFuture(Future(head.lastWrittenInt), 32)
    } yield ()
  }

  test("A Pipeline should be able to find and remove stages with identical arguments") {
    val noop = new Noop[Int]
    val p = TrunkBuilder(noop).append(new IntToString).cap(new StringEnd).base(new IntHead)

    assertEquals(p.findInboundStage(classOf[Noop[Int]]).get, noop)
    assertEquals(p.findInboundStage(noop.name).get, noop)

    noop.removeStage()

    assert(p.findInboundStage(classOf[Noop[Int]]).isEmpty)
  }

  test("A Pipeline should splice after") {
    val noop = new Noop[Int]
    val p = TrunkBuilder(new IntToString).cap(new StringEnd).base(new IntHead)
    p.spliceAfter(noop)

    assertEquals(p.findInboundStage(classOf[Noop[Int]]).get, noop)
    assertEquals(p.findInboundStage(noop.name).get, noop)

    noop.removeStage()

    assert(p.findInboundStage(classOf[Noop[Int]]).isEmpty)
  }

  test("A Pipeline should splice before") {
    val noop = new Noop[String]
    val end = new StringEnd
    val p = LeafBuilder(end).prepend(new IntToString).base(new IntHead)
    end.spliceBefore(noop)

    assertEquals(p.findInboundStage(classOf[Noop[String]]).get, noop)
    assertEquals(p.findInboundStage(noop.name).get, noop)

    noop.removeStage()

    assert(p.findInboundStage(classOf[Noop[String]]).isEmpty)
  }
}
