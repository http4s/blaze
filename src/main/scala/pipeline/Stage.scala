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


  def readRequest(): Future[O]
  final def channelRead(): Future[I] = prev.readRequest()

  def writeRequest(data: O): Future[Any]
  final def channelWrite(data: I): Future[Any] = prev.writeRequest(data)

  def replaceInline(stage: Stage[I, O]): stage.type

  def startup() {}
  def shutdown() {}

  def inboundCommand(cmd: Command): Unit = {
    defaultActions(cmd)
    next.inboundCommand(cmd)
  }

  def outboundCommand(cmd: Command): Unit = {
    defaultActions(cmd)
    prev.outboundCommand(cmd)
  }

  protected final def defaultActions(cmd: Command): Unit = cmd match {
    case Connected  => startup()
    case Removed  => shutdown()
    case _        =>   // NOOP
  }

  def replaceNext(stage: Stage[O, _]): stage.type = {
    next.shutdown()
    next.prev = null
    next = stage
    stage
  }

  def spliceAfter(stage: Stage[O, O]): stage.type = {
    next.prev = stage
    next = stage
    stage
  }

  def removeStage(implicit ev: Stage[I,O]=:=Stage[I, I]): this.type = {
    shutdown()
    val me = ev(this)
    prev.next = me.next
    me.next.prev = me.prev
    this
  }

  def findForwardStage(name: String): Option[Stage[_, _]] = {
    if (this.name == name) Some(this)
    else next.findForwardStage(name)
  }

  def findForwardStage(clazz: Class[_]): Option[Stage[_, _]] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this)
    else next.findForwardStage(clazz)
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

  final override def replaceInline(stage: Stage[I, O]): stage.type = {
    shutdown()
    prev.next = stage
    next.prev = stage
    stage
  }
}

trait TailStage[T] extends Stage[T, Any] {

  private[pipeline] var prev: Stage[_, T] = null

  override def inboundCommand(cmd: Command): Unit = defaultActions(cmd)

  override def findForwardStage(name: String): Option[Stage[_, _]] = {
    if (name == this.name) Some(this) else None
  }

  override def findForwardStage(clazz: Class[_]): Option[Stage[_, _]] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this) else None
  }

  final override def replaceInline(stage: Stage[T, Any]): stage.type = {
    shutdown()
    prev.next = stage
    stage
  }

  final override def outboundCommand(cmd: Command): Unit = {
    sys.error("TailStage doesn't receive commands: " + cmd)
  }

  final private[pipeline] override def next: Stage[Any, _] = {
    sys.error(s"TailStage ${getClass.getName} doesn't have a next stage")
  }

  final private[pipeline] override def next_=(stage: Stage[Any, _]) {
    sys.error(s"TailStage ${getClass.getName} doesn't have a next stage")
  }

  final def readRequest(): Future[Any] = {
    sys.error(s"TailStage ${getClass.getName} doesn't receive read requests")
  }

  final override def replaceNext(stage: Stage[Any, _]): stage.type = {
    sys.error(s"Stage of type TailStage doesn't have a next stage")
  }

  final override def spliceAfter(stage: Stage[Any, Any]): stage.type = {
    sys.error("TailStage cannot splice after")
  }

  final override def writeRequest(data: Any): Future[Any] = {
    sys.error("TailStage has no reason to ever receive an outbound message")
  }
}


trait HeadStage[T] extends Stage[Nothing, T] {

  private[pipeline] var next: Stage[T, _] = null

  override def outboundCommand(cmd: Command): Unit = {
    defaultActions(cmd)
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
}
