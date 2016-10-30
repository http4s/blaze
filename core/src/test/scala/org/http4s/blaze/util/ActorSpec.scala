package org.http4s.blaze.util

import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }

import org.http4s.blaze.util.Actors.Actor

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

import org.specs2.mutable.Specification

class ActorSpec extends Specification {

  val spinTime = 5.seconds

  def spin(finished: => Boolean) = TimingTools.spin(spinTime)(finished)

  case class E(msg: String) extends Exception(msg)

  sealed trait Msg
  case object NOOP extends Msg
  case class OptMsg(lst: Option[Int]) extends Msg
  case class Continuation(f: String => Any) extends Msg

  def actor(onError: Throwable => Unit = _ => ())(implicit ec: ExecutionContext): Actor[Msg] = Actors.make({
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

      val a = actor(t => flag.set(t))(global)

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
      val ii = new AtomicInteger(0)
      val a = actor()(global)
      for (_ <- 0 until 100) a ! Continuation{ _ => Thread.sleep(1); i.incrementAndGet() }
      val f = a ! Continuation(_ => ii.set(i.get()))

      spin(ii.get == 100)
      ii.get must_== 100
    }

    "Not stack overflow dewling actors with a trampolining ec" in {
      val i = new AtomicInteger(100000)
      case class Bounce(i: Int)
      implicit val ec = Execution.trampoline

      var a2: Actor[Bounce] = null

      val a1: Actor[Bounce] = Actors.make {
        case Bounce(0) => // NOOP
        case Bounce(_) => a2 ! Bounce(i.decrementAndGet)
      }
      a2 = Actors.make {
        case Bounce(0) => // NOOP
        case Bounce(_) => a1 ! Bounce(i.decrementAndGet)
      }

      a1 ! Bounce(1)    // start
      spin(i.get == 0)
      i.get must_== 0
    }
  }

  "Actor tell (`!`) pattern" should {
    "Not give exceptions in normal behavior" in {
      val flag = new AtomicInteger(0)

      actor(_ => flag.set(-1))(global) ! Continuation(_ => flag.set(1)) must_== (())
      spin(flag.get() == 1)

      flag.get must_== 1
    }

    "Deal with exceptions properly" in {
      val flag = new AtomicInteger(0)
      actor(_ => flag.set(1))(global) ! OptMsg(Some(-1))

      spin(flag.get == 1)
      flag.get must_== 1
    }

    "Deal with exceptions in the exception handling code" in {
      val i = new AtomicInteger(0)
      val a = actor(_ => sys.error("error"))(global) // this will fail the onError
      a ! OptMsg(Some(-1))  // fail the evaluation
      val f = a ! Continuation(_ => i.set(1))  // If all is ok, the actor will continue
      spin(i.get == 1)
      i.get must_== 1
    }
  }
}
