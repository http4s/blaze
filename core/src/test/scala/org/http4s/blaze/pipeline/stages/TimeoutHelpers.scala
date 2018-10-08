package org.http4s.blaze.pipeline
package stages

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import org.http4s.blaze.pipeline.Command.InboundCommand
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

abstract class TimeoutHelpers extends Specification {

  final class TimeoutTail[A](val name: String) extends TailStage[A] {
    val inboundCommandsReceived: AtomicReference[Vector[InboundCommand]] =
      new AtomicReference(Vector.empty)

    override def inboundCommand(cmd: InboundCommand) {
      inboundCommandsReceived.updateAndGet(new UnaryOperator[Vector[InboundCommand]] {
        def apply(v: Vector[InboundCommand]) = v :+ cmd
      })
      cmd match {
        case TimeoutStageBase.TimedOut(_) => closePipeline(None)
        case _ => // no-op
      }
    }
  }
  
  def genDelayStage(timeout: FiniteDuration): TimeoutStageBase[ByteBuffer]

  def newBuff: ByteBuffer = ByteBuffer.wrap("Foo".getBytes(StandardCharsets.UTF_8))

  def checkBuff(buff: ByteBuffer): MatchResult[Any] = {
    StandardCharsets.UTF_8.decode(buff).toString should_== "Foo"
  }

  def checkFuture(f: Future[ByteBuffer], timeout: Duration = 2.seconds): MatchResult[Any] = {
    val r = Await.result(f, timeout)
    checkBuff(r)
  }

  def slow(duration: Duration): DelayHead[ByteBuffer] =
    new DelayHead[ByteBuffer](duration) { def next() = newBuff }

  def makePipeline(delay: Duration, timeout: FiniteDuration): TimeoutTail[ByteBuffer] = {
    val leaf = new TimeoutTail[ByteBuffer]("TestTail")
    val head = slow(delay)
    LeafBuilder(leaf)
      .prepend(genDelayStage(timeout))
      .base(head)

    head.sendInboundCommand(Command.Connected)

    leaf
  }
}
