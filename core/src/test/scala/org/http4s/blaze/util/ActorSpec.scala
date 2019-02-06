package org.http4s.blaze.util

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import org.specs2.mutable.Specification

class ActorSpec extends Specification {

  case class E(msg: String) extends Exception(msg)

  sealed trait Msg
  case object NOOP extends Msg
  case class OptMsg(lst: Option[Int]) extends Msg
  case class Continuation(f: String => Any) extends Msg

  def actor(error: Throwable => Unit, ec: ExecutionContext): Actor[Msg] = new Actor[Msg](ec) {
    override protected def act(message: Msg): Unit = message match {
      case OptMsg(Some(-1)) => throw E("Fail.")
      case OptMsg(Some(_)) => ???
      case OptMsg(None)   => ()
      case Continuation(f) =>
        f("Completed")
        ()
      case NOOP => // NOOP
    }

    override protected def onError(t: Throwable, msg: Msg): Unit = error(t)
  }

  "Actor under load" should {

    def load(senders: Int, messages: Int): Unit = {
      val flag = new AtomicReference[Throwable]()
      val latch = new CountDownLatch(senders * messages)

      val a = actor(t => {
        flag.set(t)
        latch.countDown()
      }, global)

      for(_ <- 0 until senders) {
        global.execute(new Runnable {
          override def run(): Unit = for (_ <- 0 until messages) {
            a ! Continuation { _ => latch.countDown() }
          }
        })
      }

      latch.await(15, TimeUnit.SECONDS)  must beTrue
      if (flag.get != null) {
        throw flag.get
      }
    }

    "Handle all messages" in {
      load(1, 10000)
      load(10, 1000)
      load(100, 100)
      load(1000, 10)
      load(10000, 1)

      ok // if we get here, everything worked
    }

    "Handle messages in order" in {
      val size = 100
      var thisIteration = -1
      var outOfOrder = false
      val latch = new CountDownLatch(size)
      val a = actor(_ => ???, global)
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

      latch.await(10, TimeUnit.SECONDS) must beTrue
      outOfOrder must beFalse
    }

    "Not stack overflow dueling actors with a trampolining ec" in {
      val latch = new CountDownLatch(1)
      implicit val ec = Execution.trampoline

      lazy val a1: Actor[Int] = new Actor[Int](ec) {
        override protected def act(i: Int): Unit = {
          if (i == 0) latch.countDown()
          else  a2 ! (i - 1)
        }
      }

      lazy val a2: Actor[Int] = new Actor[Int](ec) {
        override protected def act(i: Int): Unit = {
          if (i == 0) latch.countDown()
          else  a2 ! (i - 1)
        }
      }

      a1 ! 100000    // start
      latch.await(15, TimeUnit.SECONDS) must beTrue
    }
  }

  "Actor tell (`!`) pattern" should {
    "Not give exceptions in normal behavior" in {
      val latch = new CountDownLatch(1)

      actor(_ => ???, global) ! Continuation(_ => latch.countDown())
      latch.await(10, TimeUnit.SECONDS)  must beTrue
    }

    "Deal with exceptions properly" in {
      val latch = new CountDownLatch(1)
      actor(_ => latch.countDown(), global) ! OptMsg(Some(-1))

      latch.await(10, TimeUnit.SECONDS) must beTrue
    }
  }
}
