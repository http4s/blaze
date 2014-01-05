package blaze.pipeline

import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.{Future, Await}

import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration._

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class PipelineSpec extends WordSpec with Matchers {

  class IntHead extends HeadStage[Int] {

    def name = "IntHead"

    var lastWrittenInt: Int = 0

    def writeRequest(data: Int): Future[Unit] = {
      lastWrittenInt = data
      Future.successful()
    }

    def readRequest(): Future[Int] = Future(54)
  }

  class IntToString extends MiddleStage[Int, String] {

    def name = "IntToString"

    def readRequest(): Future[String] = channelRead map (_.toString)

    def writeRequest(data: String): Future[Any] = {
      try channelWrite(data.toInt)
      catch { case t: NumberFormatException => Future.failed(t) }
    }
  }

  class StringEnd extends TailStage[String] {
    def name: String = "StringEnd"

    var lastString = ""
  }

  "Pipeline" should {
    "Make a basic blaze.pipeline" in {
      val head = new IntHead
      val tail = new StringEnd

      val p = new RootBuilder(head)
      p.addLast(new IntToString)
        .addLast(tail)
        .result

      println(head)
      val r = tail.channelRead()
      Await.result(r, 1.second) should equal("54")
      Await.ready(tail.channelWrite("32"), 1.second)

      head.lastWrittenInt should equal(32)

    }

    "Be able to find and remove stages with identical arguments" in {

      class Noop extends MiddleStage[Int, Int] {
        def name: String = "NOOP"

        def readRequest(): Future[Int] = channelRead()

        def writeRequest(data: Int): Future[Any] = channelWrite(data)
      }

      val s = new Noop
      val p = new RootBuilder(new IntHead)
                  .addLast(s)
                  .addLast(new IntToString)
                  .addLast(new StringEnd)
                  .result

      p.findStageByClass(classOf[Noop]).get should equal(s)
      p.findStageByName(s.name).get should equal(s)
      s.removeStage
      p.findStageByClass(classOf[Noop]).isDefined should equal(false)
    }
  }



}
