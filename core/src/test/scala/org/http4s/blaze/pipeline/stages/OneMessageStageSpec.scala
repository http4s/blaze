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
