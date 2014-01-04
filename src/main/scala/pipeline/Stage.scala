package pipeline

import scala.reflect.ClassTag
import scala.concurrent.Future
import pipeline.Command._
import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */

trait Stage[I, O] extends Logging {

  def name: String

  private[pipeline] var prev: Stage[_, I]
  private[pipeline] var next: Stage[O, _]

  protected def iclass: Class[I]
  protected def oclass: Class[O]

  def handleInbound(data: I): Future[Unit]
  def handleOutbound(data: O): Future[Unit]

  def startup() {}
  def cleanup() {}

  def inboundCommand(cmd: Command): Unit = {
    defaultActions(cmd)
    next.inboundCommand(cmd)
  }

  def outboundCommand(cmd: Command): Unit = {
    defaultActions(cmd)
    prev.outboundCommand(cmd)
  }

  protected final def defaultActions(cmd: Command): Unit = cmd match {
    case Startup  => startup()
    case Removed  => startup()
    case _        =>   // NOOP
  }

  def replaceInline(stage: Stage[I, O]): stage.type
  
  protected def untypedOutbound(data: AnyRef): Future[Unit] = {
    if (oclass.isAssignableFrom(data.getClass))
      handleOutbound(data.asInstanceOf[O])
    else prev.untypedInbound(data)
  }

  protected def untypedInbound(data: AnyRef): Future[Unit] = {
    if (iclass.isAssignableFrom(data.getClass))
      handleInbound(data.asInstanceOf[I])
    else next.untypedInbound(data)
  }

  def findForwardStage(name: String): Option[Stage[_, _]] = {
    if (this.name == name) Some(this)
    else next.findForwardStage(name)
  }

  def findForwardStage(clazz: Class[_]): Option[Stage[_, _]] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this)
    else next.findForwardStage(clazz)
  }

  def findStageHandling[A:ClassTag]: Option[Stage[A, _]] = {
    if (iclass.isAssignableFrom(iclass)) Some(this.asInstanceOf[Stage[A, _]])
    else next.findStageHandling[A]
  }

  def getLastStage: Stage[_, _] = if (next == null) this else next
}
