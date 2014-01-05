package pipeline

import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.{Future, Await}

import scala.concurrent.duration._

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class PipelineSpec extends WordSpec with Matchers {

  class IntHead extends HeadStage[Int] {

    def name = "IntHead"

    var lastInt: Int = 0

    def handleOutbound(data: Int): Future[Unit] = {
      lastInt = data
      Future.successful()
    }
  }

  class IntToString extends MiddleStage[Int, String] {

    def name = "IntToString"

    protected def iclass: Class[Int] = classOf[Int]

    protected def oclass: Class[String] = classOf[String]

    def handleInbound(data: Int): Future[Unit] = next.handleInbound(data.toString)

    def handleOutbound(data: String): Future[Unit] = prev.handleOutbound(data.toInt)
  }

  class StringEnd extends TailStage[String] {
    def name: String = "StringEnd"

    var lastString = ""

    protected def iclass: Class[String] = classOf[String]

    def handleInbound(data: String): Future[Unit] = {
      println(s"StringEnd recieved message: $data. Echoing")
      prev.handleOutbound(data)
    }
  }

  "Pipeline" should {
    "Make a basic pipeline" in {
      val head = new IntHead
      val p = new RootBuilder(head)
      p.addLast(new IntToString)
        .addLast(new StringEnd)
        .result

      println(head)
      val r = head.sendInbound(4)
      Await.ready(r, 1.second)
      head.lastInt should equal(4)

    }
  }

}
