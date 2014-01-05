package pipeline

import scala.reflect.ClassTag
import scala.concurrent.Future
import pipeline.Command._
import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */

sealed trait Stage[I, O] extends Logging {

  def name: String

  private[pipeline] var prev: Stage[_, I]
  private[pipeline] var next: Stage[O, _]

  protected def iclass: Class[I]
  protected def oclass: Class[O]

  def handleInbound(data: I): Future[Unit]
  def handleOutbound(data: O): Future[Unit]

  def sendInbound(data: O): Future[Unit] = next.handleInbound(data)
  def sendOutbound(data: I): Future[Unit] = prev.handleOutbound(data)

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
    case Removed  => cleanup()
    case _        =>   // NOOP
  }

  def replaceInline(stage: Stage[I, O]): stage.type = {
    cleanup()
    prev.next = stage
    next.prev = stage
    stage
  }

  def spliceAfter(stage: Stage[O, O]): stage.type = {
    next.prev = stage
    next = stage
    stage
  }

  def removeStage(implicit ev: Stage[I,O]=:=Stage[I, I]): this.type = {
    cleanup()
    val me = ev(this)
    prev.next = me.next
    me.next.prev = me.prev
    this
  }
  
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

  def getLastStage: Stage[_, _] = {
    if (next == null) this else next.getLastStage
  }

  override def toString: String = {
    s"Pipeline Stage ${getClass.getName}"
  }
}

trait MiddleStage[I, O] extends Stage[I, O] {
  var prev: Stage[_, I] = null
  var next: Stage[O, _] = null
}

trait TailStage[T] extends Stage[T, Any] {

  protected final def oclass: Class[Any] = ???

  private[pipeline] var prev: Stage[_, T] = null

  final private[pipeline] override def next: Stage[Any, _] = {
    sys.error(s"CapStage ${getClass.getName} doesn't have a next stage")
  }

  final private[pipeline] override def next_=(stage: Stage[Any, _]) {
    sys.error(s"CapStage ${getClass.getName} doesn't have a next stage")
  }

  final override def sendInbound(data: Any): Future[Unit] = {
    sys.error(s"CapStage ${getClass.getName} doesn't have a next stage")
  }

  final def replaceNext(stage: Stage[Any, _]): stage.type = {
    sys.error(s"Stage of type CapStage doesn't have a next stage")
  }

  override def inboundCommand(cmd: Command): Unit = defaultActions(cmd)

  final override def outboundCommand(cmd: Command): Unit = {
    sys.error("CapStage doesn't receive commands: " + cmd)
  }

  override def replaceInline(stage: Stage[T, Any]): stage.type = {
    cleanup()
    prev.next = stage
    stage
  }

  final def handleOutbound(data: Any): Future[Unit] = {
    sys.error("CapStage has no reason to ever receive an outbound message")
  }

  final override protected def untypedOutbound(data: AnyRef): Future[Unit] = {
    sys.error("CapStage shouldn't receive messages: " + data)
  }

  final override protected def untypedInbound(data: AnyRef): Future[Unit] = {
    if (iclass.isAssignableFrom(data.getClass))
      handleInbound(data.asInstanceOf[T])
    else {
      logger.warn(s"CapStage ${getClass.getName} is dropping message $data")
      Future.successful()
    }
  }

  override def findForwardStage(name: String): Option[Stage[_, _]] = {
    if (name == this.name) Some(this) else None
  }

  override def findForwardStage(clazz: Class[_]): Option[Stage[_, _]] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this) else None
  }

  override def findStageHandling[A: ClassTag]: Option[Stage[A, _]] = {
    val clazz = implicitly[ClassTag[A]].runtimeClass

    if (clazz.isAssignableFrom(iclass)) Some(this.asInstanceOf[Stage[A,_]])
    else None
  }
}

trait HeadStage[T] extends Stage[Nothing, T] {

  private[pipeline] var next: Stage[T, _] = null


  protected final def oclass: Class[T] = {
    sys.error("HeadStage doesn't have an outbound type")
  }

  override protected def untypedOutbound(data: AnyRef): Future[Unit] = {
    if (oclass.isAssignableFrom(data.getClass))
      handleOutbound(data.asInstanceOf[T])
    else {
      logger.warn(s"Data $data reached head of the pipeline unhandled. Dropping.")
      Future.successful()
    }
  }

  override def outboundCommand(cmd: Command): Unit = {
    defaultActions(cmd)
  }

  final protected def iclass: Class[Nothing] = {
    sys.error("HeadStage doesn't have an inbound class")
  }

  final def handleInbound(data: Nothing): Future[Unit] = {
    sys.error("HeadStage doesn't receive data directly")
  }

  final override def replaceInline(stage: Stage[Nothing, T]): stage.type = {
    sys.error("Cannot replace HeadStage")
  }

  override private[pipeline] def prev: Stage[_, Nothing] = {
    sys.error("HeadStage doesn't have a previous node")
  }

  override private[pipeline] def prev_=(stage: Stage[_, Nothing]) {
    sys.error("HeadStage doesn't have a previous node")
  }

  final override def sendOutbound(data: Nothing): Future[Unit] = {
    sys.error("HeadStage doesn't have a previous node")
  }
}
