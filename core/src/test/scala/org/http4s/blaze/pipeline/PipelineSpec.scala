/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.pipeline

import org.http4s.blaze.util.{Execution, FutureUnit}
import org.specs2.mutable._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class PipelineSpec extends Specification {
  private implicit def ec = Execution.trampoline

  class IntHead extends HeadStage[Int] {
    def name = "IntHead"

    override protected def doClosePipeline(cause: Option[Throwable]): Unit = ???

    @volatile
    var lastWrittenInt: Int = 0
    def writeRequest(data: Int): Future[Unit] = {
      lastWrittenInt = data
      FutureUnit
    }
    def readRequest(size: Int): Future[Int] = Future(54)
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

  "Pipeline" should {
    "Make a basic org.http4s.blaze.pipeline" in {
      val head = new IntHead
      val tail = new StringEnd

      TrunkBuilder(new IntToString).cap(tail).base(head)

      val r = tail.channelRead()
      Await.result(r, 60.seconds) should_== "54"
      Await.ready(tail.channelWrite("32"), 60.seconds)

      head.lastWrittenInt should_== 32
    }

    "Be able to find and remove stages with identical arguments" in {
      val noop = new Noop[Int]
      val p = TrunkBuilder(noop).append(new IntToString).cap(new StringEnd).base(new IntHead)

      p.findInboundStage(classOf[Noop[Int]]).get should_== noop
      p.findInboundStage(noop.name).get should_== noop
      noop.removeStage
      p.findInboundStage(classOf[Noop[Int]]) must_== None
    }

    "Splice after" in {
      val noop = new Noop[Int]
      val p = TrunkBuilder(new IntToString).cap(new StringEnd).base(new IntHead)
      p.spliceAfter(noop)

      p.findInboundStage(classOf[Noop[Int]]).get should_== noop
      p.findInboundStage(noop.name).get should_== noop
      noop.removeStage
      p.findInboundStage(classOf[Noop[Int]]) must_== None
    }

    "Splice before" in {
      val noop = new Noop[String]
      val end = new StringEnd
      val p = LeafBuilder(end).prepend(new IntToString).base(new IntHead)
      end.spliceBefore(noop)

      p.findInboundStage(classOf[Noop[String]]).get should_== noop
      p.findInboundStage(noop.name).get should_== noop
      noop.removeStage
      p.findInboundStage(classOf[Noop[String]]) must_== None
    }
  }
}
