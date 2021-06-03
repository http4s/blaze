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

import munit.FunSuite
import org.http4s.blaze.pipeline.{HeadStage, LeafBuilder, TailStage}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class OneMessageStageSuite extends FunSuite {
  test("A OneMessageStage should return its single element and then eject itself") {
    val head = new HeadStage[Int] {
      protected def doClosePipeline(cause: Option[Throwable]): Unit = ()
      def readRequest(size: Int): Future[Int] = Future.successful(1)
      def writeRequest(data: Int): Future[Unit] = Future.unit
      def name: String = "TestHeadStage"
    }
    val oneMsg = new OneMessageStage[Int](1)
    val tail = new TailStage[Int] {
      override def name: String = "Boring"
    }

    // Zip up the pipeline
    LeafBuilder(tail).prepend(oneMsg).base(head)

    assertEquals(tail.findOutboundStage(classOf[OneMessageStage[Int]]), Some(oneMsg))

    assertEquals(Await.result(tail.channelRead(), 2.seconds), 1)

    assert(tail.findOutboundStage(classOf[OneMessageStage[Int]]).isEmpty)
  }
}
