package org.http4s.blaze.pipeline
package stages

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.pipeline.Command.InboundCommand
import org.http4s.blaze.util.Execution.directec
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Success, Failure}

abstract class TimeoutHelpers extends Specification {

  final class TimeoutTail[A](val name: String) extends TailStage[A] {
    override protected def stageStartup(): Unit = {
      implicit val ec = directec
      super.stageStartup()
      findOutboundStage(classOf[TimeoutStage[_, _]]).foreach(_.timedOut.onComplete {
        case Success(()) => closePipeline(None)
        case Failure(t) => closePipeline(Some(t))
      })
    }
  }

  def genDelayStage(timeout: FiniteDuration): TimeoutStage[String, String]

  val chunk = "Chunk"

  def checkFuture[A](f: Future[A], timeout: Duration = 2.seconds): MatchResult[Any] = {
    Await.result(f, timeout) must_== chunk
  }

  def slow(duration: Duration): DelayHead[String] =
    new DelayHead[String](duration) { def next() = chunk }

  def makePipeline(delay: Duration, timeout: FiniteDuration): TimeoutTail[String] = {
    val leaf = new TimeoutTail[String]("TestTail")
    val head = slow(delay)
    LeafBuilder(leaf)
      .prepend(genDelayStage(timeout))
      .base(head)

    head.sendInboundCommand(Command.Connected)

    leaf
  }
}
