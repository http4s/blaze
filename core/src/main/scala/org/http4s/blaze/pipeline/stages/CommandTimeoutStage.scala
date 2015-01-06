package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command.{ OutboundCommand, InboundCommand }
import org.http4s.blaze.pipeline.stages.CommandTimeoutStage.{ TimeoutCancel, TimeoutBegin }
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.duration.Duration


class CommandTimeoutStage[T](timeout: Duration, exec: TickWheelExecutor = scheduler)
  extends TimeoutStageBase[T](timeout, exec) {
  // Overrides to propagate commands.
  override def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
    case TimeoutBegin => resetTimeout()

    case TimeoutCancel => cancelTimeout()

    case _ => super.outboundCommand(cmd)
  }

  // Overrides to propagate commands.
  override def inboundCommand(cmd: InboundCommand): Unit = cmd match {
    case TimeoutBegin => resetTimeout()
    case TimeoutCancel => cancelTimeout()
    case _ => super.inboundCommand(cmd)
  }
}

object CommandTimeoutStage {
  object TimeoutBegin extends InboundCommand with OutboundCommand
  object TimeoutCancel extends InboundCommand with OutboundCommand
}

