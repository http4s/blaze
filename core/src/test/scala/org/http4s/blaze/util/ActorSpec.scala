package org.http4s.blaze.util

import java.util.concurrent.atomic.{AtomicReference, AtomicInteger, AtomicBoolean}

import org.http4s.blaze.util.Actors.Actor

import scala.concurrent.duration._
import scala.concurrent.Await

import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions




class ActorSpec extends Specification with NoTimeConversions {

  import scala.concurrent.ExecutionContext.Implicits.global

  val spinTime = 5.seconds

  def spin(finished: => Boolean): Unit = {
    val start = System.nanoTime()
    while(!finished && System.nanoTime() - start < spinTime.toNanos) {
      Thread.sleep(1) /* spin up to 5 seconds */
    }
  }

  case class E(msg: String) extends Exception(msg)

  sealed trait Msg
  case object NOOP extends Msg
  case class OptMsg(lst: Option[Int]) extends Msg
  case class Continuation(f: String => Any) extends Msg

  def actor(onError: Throwable => Unit = _ => ()): Actor[Msg] = Actors.make({
    case OptMsg(Some(-1)) => throw E("Fail.")
    case OptMsg(Some(_)) => ???
    case OptMsg(None)   => "empty"
    case Continuation(f) => f("Completed")

    case NOOP => // NOOP
  }, (t, _) => onError(t))



  "Actor under load" should {

    def load(senders: Int, messages: Int): Int = {
      val flag = new AtomicReference[Throwable]()
      val acc = new AtomicInteger(0)

      val a = actor(t => flag.set(t))

      for(i <- 0 until senders) {
        global.execute(new Runnable {
          override def run(): Unit = for (i <- 0 until messages) {
            a ! Continuation{ _ => acc.incrementAndGet() }
          }
        })
      }

      spin(flag.get != null || acc.get == senders*messages)
      acc.get
    }

    "Handle all messages" in {
      load(1, 10000) must_== 10000
      load(10, 1000) must_== 10000
      load(100, 100) must_== 10000
      load(1000, 10) must_== 10000
      load(10000, 1) must_== 10000
    }

    "Handle messages in order" in {
      val i = new AtomicInteger(0)
      val a = actor()
      for (_ <- 0 until 100) a ! Continuation{ _ => Thread.sleep(1); i.incrementAndGet() }
      val f = a ? Continuation(_ => i.get())

      Await.result(f, 5.seconds) must_== 100
    }
  }

  "Actor tell (`!`) pattern" should {
    "Not give exceptions in normal behavior" in {
      val flag = new AtomicInteger(0)

      actor(_ => flag.set(-1)) ! Continuation(_ => flag.set(1)) must_== (())
      spin(flag.get() == 1)

      flag.get must_== 1
    }

    "Deal with exceptions properly" in {
      val flag = new AtomicInteger(0)
      actor(_ => flag.set(1)) ! OptMsg(Some(-1))

      spin(flag.get != 0)
      flag.get must_== 1
    }

    "Deal with exceptions in the exception handling code" in {
      val a = actor(_ => sys.error("error")) // this will fail the onError
      a ! OptMsg(Some(-1))  // fail the evaluation
      val f = a ? OptMsg(None)  // If all is ok, the actor will continue
      Await.result(f, 5.seconds) must_== "empty"
    }
  }

  "Actor ask (`?`) pattern" should {
    "Complete successfully" in {
      val f = actor() ? OptMsg(None)

      Await.result(f, 5.seconds) must_== "empty"
    }

    "Handle errors in ask (`?`) pattern" in {
      val f = actor() ? OptMsg(Some(-1))

      Await.result(f, 5.seconds) must throwA[E]
    }
  }
}
