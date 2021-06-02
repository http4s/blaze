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

package org.http4s.blaze.util

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

class ActorSuite extends FunSuite {
  private case class E(msg: String) extends Exception(msg)

  private sealed trait Msg
  private case object NOOP extends Msg
  private case class OptMsg(lst: Option[Int]) extends Msg
  private case class Continuation(f: String => Any) extends Msg

  private def actor(error: Throwable => Unit, ec: ExecutionContext): Actor[Msg] =
    new Actor[Msg](ec) {
      override protected def act(message: Msg): Unit =
        message match {
          case OptMsg(Some(-1)) => throw E("Fail.")
          case OptMsg(Some(_)) => ()
          case OptMsg(None) => ()
          case Continuation(f) =>
            f("Completed")
            ()
          case NOOP => // NOOP
        }

      override protected def onError(t: Throwable, msg: Msg): Unit = error(t)
    }

  private def load(senders: Int, messages: Int): Unit = {
    val flag = new AtomicReference[Throwable]()
    val latch = new CountDownLatch(senders * messages)

    val a = actor(
      t => {
        flag.set(t)
        latch.countDown()
      },
      global)

    for (_ <- 0 until senders)
      global.execute(() =>
        for (_ <- 0 until messages)
          a ! Continuation(_ => latch.countDown()))

    assert(latch.await(15, TimeUnit.SECONDS))

    if (flag.get != null) throw flag.get
  }

  test("An actor under load should handle all messages") {
    load(1, 10000)
    load(10, 1000)
    load(100, 100)
    load(1000, 10)
    load(10000, 1)
  }

  test("An actor under load should handle messages in order") {
    val size = 100
    var thisIteration = -1
    var outOfOrder = false
    val latch = new CountDownLatch(size)
    val a = actor(_ => (), global)
    for (i <- 0 until size) a ! Continuation { _ =>
      // sleep a little to allow things to get out of order they are going to
      Thread.sleep(1)

      if (thisIteration != i - 1) {
        outOfOrder = true
      } else {
        thisIteration += 1
      }

      latch.countDown()
    }

    assert(latch.await(10, TimeUnit.SECONDS))

    assertEquals(outOfOrder, false)
  }

  test(
    "An actor under load shouldn't have a stack overflow dueling actors with a trampolining ec") {
    val latch = new CountDownLatch(1)
    implicit val ec = Execution.trampoline

    lazy val a1: Actor[Int] = new Actor[Int](ec) {
      override protected def act(i: Int): Unit =
        if (i == 0) latch.countDown()
        else a2 ! (i - 1)
    }

    lazy val a2: Actor[Int] = new Actor[Int](ec) {
      override protected def act(i: Int): Unit =
        if (i == 0) latch.countDown()
        else a2 ! (i - 1)
    }

    a1 ! 100000 // start
    assert(latch.await(15, TimeUnit.SECONDS))
  }

  test("Actor tell (`!`) pattern shouldn't give exceptions in normal behavior") {
    val latch = new CountDownLatch(1)

    actor(_ => (), global) ! Continuation(_ => latch.countDown())

    assert(latch.await(10, TimeUnit.SECONDS))
  }

  test("Actor tell (`!`) pattern should deal with exceptions properly") {
    val latch = new CountDownLatch(1)
    actor(_ => latch.countDown(), global) ! OptMsg(Some(-1))

    assert(latch.await(10, TimeUnit.SECONDS))
  }
}
