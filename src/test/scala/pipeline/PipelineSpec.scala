package pipeline

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
    "Make a basic pipeline" in {
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
  }

}
