package org.http4s.blaze.pipeline.stages

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
  def bufferTail = new TailStage[ByteBuffer] { def name = "ByteBuffer Tail" }

  def makePipeline(delay: Duration, timeout: Duration): TailStage[ByteBuffer] = {
    val leaf = bufferTail
    val head = slow(delay)
    LeafBuilder(leaf)
      .prepend(genDelayStage(timeout))
      .base(head)

    head.sendInboundCommand(Command.Connected)

    leaf
  }
}
