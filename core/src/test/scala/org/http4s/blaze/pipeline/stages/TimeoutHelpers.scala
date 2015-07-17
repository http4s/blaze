package org.http4s.blaze.pipeline.stages

import java.util.concurrent.atomic.AtomicInteger

import org.http4s.blaze.pipeline.Command.{Disconnected, InboundCommand}
import org.specs2.mutable.Specification

import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}

import java.nio.ByteBuffer

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

abstract class TimeoutHelpers extends Specification {
  
  def genDelayStage(timeout: Duration): TimeoutStageBase[ByteBuffer]

  def newBuff = ByteBuffer.wrap("Foo".getBytes())

  def checkBuff(buff: ByteBuffer) = {
    val arr = new Array[Byte]("Foo".getBytes().length)
    buff.get(arr)
    new String(arr) should_== "Foo"
  }

  def checkFuture(f: Future[ByteBuffer], timeout: Duration = 2.seconds) = {
    val r = Await.result(f, timeout)
    checkBuff(r)
  }

  def slow(duration: Duration) = new DelayHead[ByteBuffer](duration) { def next() = newBuff }

  def makePipeline(delay: Duration, timeout: Duration): TestTail = {
    val leaf = new TestTail
    val head = slow(delay)
    LeafBuilder(leaf)
      .prepend(genDelayStage(timeout))
      .base(head)

    head.sendInboundCommand(Command.Connected)

    leaf
  }

  class TestTail extends TailStage[ByteBuffer] {
    def name = "TestTail"

    private val disconnectCount = new AtomicInteger(0)

    def getDisconnects = disconnectCount.get()

    override def inboundCommand(cmd: InboundCommand): Unit = {
      super.inboundCommand(cmd)
      cmd match {
        case Disconnected => disconnectCount.incrementAndGet()
        case _ => ()
      }
    }
  }
}
