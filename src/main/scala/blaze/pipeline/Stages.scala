package blaze.pipeline

import java.util.Date

import scala.concurrent.Future
import Command._
import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */

trait TailStage[I] extends Logging {
  
  def name: String

  private[pipeline] var prev: MidStage[_, I] = null

  protected def startup(): Unit = logger.trace(s"Starting up at ${new Date}")
  protected def shutdown(): Unit = logger.trace(s"Shutting down at ${new Date}")

  final def channelRead(size: Int = -1): Future[I] = {
    logger.trace(s"Stage ${getClass.getName} sending read request.")

    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot request read")

    prev.readRequest(size)
  }

  final def channelWrite(data: I): Future[Any] = {
    logger.trace(s"Stage ${getClass.getName} sending write request.")

    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot write downstream")

    prev.writeRequest(data)
  }

  final def sendOutboundCommand(cmd: Command): Unit = {
    this match {
      case _: HeadStage[_] => // NOOP
      case _               => prev.outboundCommand(cmd)
    }
  }

  def outboundCommand(cmd: Command): Unit = sendOutboundCommand(cmd)

  def inboundCommand(cmd: Command): Unit = {
    cmd match {
      case Connected => startup()
      case Shutdown  => shutdown()
      case _         => // NOOP
    }
  }

  final def replaceInline(stage: TailStage[I]): stage.type = {
    shutdown()
    if (!this.isInstanceOf[HeadStage[_]]) prev.next = stage
    stage
  }

  override def toString: String = {
    s"Pipeline Stage ${getClass.getName}"
  }
}

trait MidStage[I, O] extends TailStage[I] {

//  private[pipeline] var prev: MidStage[_, I]
  private[pipeline] var next: TailStage[O] = null

  def readRequest(size: Int): Future[O]

  def writeRequest(data: O): Future[Any]

  override def inboundCommand(cmd: Command): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
  }

  final def sendInboundCommand(cmd: Command): Unit = next.inboundCommand(cmd)

  //////////////////////////////////////////////////////////////////////////////

  final def replaceInline(stage: MidStage[I, O]): stage.type = {
    shutdown()
    next.prev = stage
    if (!this.isInstanceOf[HeadStage[_]]) prev.next = stage
    stage
  }

  final def replaceNext(stage: TailStage[O]): stage.type = {
    next.replaceInline(stage)
    stage
  }

  final def spliceAfter(stage: MidStage[O, O]): stage.type = {
    next.prev = stage
    next = stage
    stage
  }

  final def removeStage(implicit ev: MidStage[I,O]=:=MidStage[I, I]): this.type = {
    shutdown()

    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage be removed!")

    val me = ev(this)
    prev.next = me.next
    me.next.prev = me.prev
    this
  }

  final def findStageByName(name: String): Option[TailStage[_]] = {
    if (this.name == name) Some(this)
    else next match {
      case s: MidStage[_, _] => s.findStageByName(name)
      case t: TailStage[_]   => if (t.name == name) Some(t) else None
    }
  }

  final def findStageByClass[C <: TailStage[_]](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else next match {
      case s: MidStage[_, _]              => s.findStageByClass(clazz)
      case t: TailStage[_] =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }

  final def getLastStage: TailStage[_] = next match {
    case s: MidStage[_, _] => s.getLastStage
    case t: TailStage[_]        => t
  }
}

/** Headstage represents the front of the pipeline, interacting with whatever
  * external data source dictated to service its tail nodes.
  * WARNING! Do not attempt to use the channelWrite or channelRead, as they attempt
  * to access an upstream stage, which doesn't exist for a head node
  *
  * @tparam O type of data which this stage can submit
  */
trait HeadStage[O] extends MidStage[Nothing, O]
