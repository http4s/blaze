package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.{HeadStage, LeafBuilder, TailStage}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration._

class OneMessageStageSpec extends Specification with Mockito {
  "OneMessageStage" should {
    "Return its single element and then eject itself" in {

      val head = mock[HeadStage[Int]]
      val oneMsg = new OneMessageStage[Int](1)
      val tail = new TailStage[Int] {
        override def name: String = "Boring"
      }

      // Zip up the pipeline
      LeafBuilder(tail).prepend(oneMsg).base(head)

      tail.findOutboundStage(classOf[OneMessageStage[Int]]) must beSome(oneMsg)

      Await.result(tail.channelRead(), 2.seconds) must_== 1

      tail.findOutboundStage(classOf[OneMessageStage[Int]]) must beNone
    }
  }
}
