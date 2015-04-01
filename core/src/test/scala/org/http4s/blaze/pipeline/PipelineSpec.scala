package org.http4s.blaze.pipeline

import org.http4s.blaze.util.Execution
import org.specs2.mutable._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

class PipelineSpec extends Specification {

  implicit def ec = Execution.trampoline

  class IntHead extends HeadStage[Int] {

    def name = "IntHead"

    @volatile
    var lastWrittenInt: Int = 0

    def writeRequest(data: Int): Future[Unit] = {
      lastWrittenInt = data
      Future.successful(())
    }

    def readRequest(size: Int): Future[Int] = Future(54)
  }

  class IntToString extends MidStage[Int, String] {

    def name = "IntToString"

    def readRequest(size: Int): Future[String] = channelRead(1) map (_.toString)

    def writeRequest(data: String): Future[Unit] = {
      try channelWrite(data.toInt)
      catch { case t: NumberFormatException => Future.failed(t) }
    }
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

      class Noop extends MidStage[Int, Int] {
        def name: String = "NOOP"

        def readRequest(size: Int): Future[Int] = channelRead(size)

        def writeRequest(data: Int): Future[Unit] = channelWrite(data)
      }

      val noop = new Noop

      val p = TrunkBuilder(noop).append(new IntToString).cap(new StringEnd).base(new IntHead)

      p.findInboundStage(classOf[Noop]).get should_== noop
      p.findInboundStage(noop.name).get should_== noop
      noop.removeStage
      p.findInboundStage(classOf[Noop]).isDefined should_== false
    }

  }



}
