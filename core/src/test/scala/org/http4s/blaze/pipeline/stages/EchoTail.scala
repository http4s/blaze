package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command.{EOF, InboundCommand}
import org.http4s.blaze.pipeline.TailStage

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.concurrent.Promise


class EchoTail[A] extends TailStage[A] {
  override def name: String = "Echo Tail"

  def startLoop(): Future[Unit] = {
    val p = Promise[Unit]
    innerLoop(p)
    p.future
  }

  private def innerLoop(p: Promise[Unit]): Unit = {
    channelRead(-1, 20.seconds).flatMap { buff =>
      channelWrite(buff)
    }.onComplete{
      case Success(_) => innerLoop(p)
      case Failure(EOF) => p.success(())
      case e            => p.complete(e)
    }
  }
}
