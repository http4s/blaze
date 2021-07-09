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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.concurrent.Future
import scala.concurrent.duration._

abstract class TimeoutHelpers extends BlazeTestSuite {
  def genDelayStage(timeout: Duration): TimeoutStageBase[ByteBuffer]

  def newBuff: ByteBuffer = ByteBuffer.wrap("Foo".getBytes(StandardCharsets.UTF_8))

  def checkFuture(f: => Future[ByteBuffer]): Future[Unit] =
    assertFuture(f.map(StandardCharsets.UTF_8.decode(_).toString), "Foo")

  def slow(duration: Duration): DelayHead[ByteBuffer] =
    new DelayHead[ByteBuffer](duration) { def next() = newBuff }

  def makePipeline(delay: Duration, timeout: Duration): BasicTail[ByteBuffer] = {
    val leaf = new BasicTail[ByteBuffer]("TestTail")
    val head = slow(delay)
    LeafBuilder(leaf)
      .prepend(genDelayStage(timeout))
      .base(head)

    head.sendInboundCommand(Command.Connected)

    leaf
  }
}
