package blaze.pipeline

import java.util.Date

import scala.concurrent.Future
import Command._
import com.typesafe.scalalogging.slf4j.Logging
import blaze.pipeline.PipelineBuilder.CapStage
import blaze.util.Execution.directec

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */

trait TailStage[I] extends Logging {
  
  def name: String

  private[pipeline] var prev: MidStage[_, I] = null

  protected def stageStartup(): Unit = logger.trace(s"Starting up at ${new Date}")
  protected def stageShutdown(): Unit = logger.trace(s"Shutting down at ${new Date}")

  final def channelRead(size: Int = -1): Future[I] = {
    logger.trace(s"Stage ${getClass.getName} sending read request.")

    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot request read")

    try prev.readRequest(size)
    catch { case t: Throwable => Future.failed(t) }
  }

  final def channelWrite(data: Seq[I]): Future[Any] = {
    logger.trace(s"Stage ${getClass.getName} sending multiple write request.")

    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot write downstream")

    try prev.writeRequest(data)
    catch { case t: Throwable => Future.failed(t) }
  }

  final def channelWrite(data: I): Future[Any] = {
    logger.trace(s"Stage ${getClass.getName} sending write request.")

    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot write downstream")

    try prev.writeRequest(data)
    catch { case t: Throwable => Future.failed(t) }
  }

  final def sendOutboundCommand(cmd: Command): Unit = this match {
    case _: HeadStage[_] => sys.error("HeadStage cannot send outbound commands!")// NOOP
    case _ =>
      try prev.outboundCommand(cmd)
      catch { case t: Throwable => inboundCommand(Error(t)) }
  }

  def inboundCommand(cmd: Command): Unit = {
    cmd match {
      case Connected => stageStartup()
      case Shutdown  => stageShutdown()
      case _         => // NOOP
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  final def replaceInline(stage: TailStage[I]): this.type = {
    stageShutdown()

    stage.prev = this.prev
    if (!this.isInstanceOf[HeadStage[_]]) prev.next = stage

    // remove links to other stages
    this.prev = null
    this match {
      case m: MidStage[_, _] => m.next = null
      case _ => // NOOP
    }
    stage.inboundCommand(Command.Connected)
    this
  }

  final def findOutboundStage(name: String): Option[TailStage[_]] = {
    if (this.name == name) Some(this)
    else prev match {
      case t: HeadStage[_]   => if (t.name == name) Some(t) else None
      case s: MidStage[_, _] => s.findOutboundStage(name)
    }
  }

  final def findOutboundStage[C <: MidStage[_, _]](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else prev match {
      case t: HeadStage[_] =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None

      case s: MidStage[_, _] => s.findOutboundStage[C](clazz)
      case _ => sys.error("Shouldn't get here.")
    }
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

  def writeRequest(data: Seq[O]): Future[Any] = {
    data.foldLeft[Future[Any]](Future.successful()){ (f, d) =>
      f.flatMap(_ => writeRequest(d))(directec)
    }
  }

  override def inboundCommand(cmd: Command): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
  }

  def outboundCommand(cmd: Command): Unit = {
    cmd match {
      case Connected => stageStartup()
      case Shutdown  => stageShutdown()
      case _         => // NOOP
    }
    sendOutboundCommand(cmd)
  }

  final def sendInboundCommand(cmd: Command): Unit = {
    try next.inboundCommand(cmd)
    catch { case t: Throwable => outboundCommand(Error(t)) }
  }

  //////////////////////////////////////////////////////////////////////////////

  final def replaceInline(stage: MidStage[I, O]): this.type = {
    stageShutdown()
    next.prev = stage
    if (!this.isInstanceOf[HeadStage[_]]) prev.next = stage
    this.next = null
    this.prev = null
    this
  }

  final def replaceNext(stage: TailStage[O]): TailStage[O] = {
    next.replaceInline(stage)
  }

  final def spliceAfter(stage: MidStage[O, O]): stage.type = {
    next.prev = stage
    next = stage
    stage
  }

  final def removeStage(implicit ev: MidStage[I,O]=:=MidStage[I, I]): this.type = {
    stageShutdown()
    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage be removed!")

    val me = ev(this)
    prev.next = me.next
    me.next.prev = me.prev

    next = null
    prev = null
    this
  }

  final def findInboundStage(name: String): Option[TailStage[_]] = {
    if (this.name == name) Some(this)
    else next match {
      case s: MidStage[_, _] => s.findInboundStage(name)
      case t: TailStage[_]   => if (t.name == name) Some(t) else None
    }
  }

  final def findInboundStage[C <: TailStage[_]](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else next match {
      case s: MidStage[_, _] => s.findInboundStage[C](clazz)
      case t: TailStage[_] =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }

  final def getLastStage: TailStage[_] = next match {
    case s: MidStage[_, _] if s.next != null => s.getLastStage
    case c: CapStage                         => this
    case n                                   => n
  }
}

/** HeadStage represents the front of the pipeline, interacting with whatever
  * external data source dictated to service its tail nodes.
  * WARNING! Do not attempt to use the channelWrite or channelRead, as they attempt
  * to access an upstream stage, which doesn't exist for a head node
  *
  * @tparam O type of data which this stage can submit
  */
trait HeadStage[O] extends MidStage[Nothing, O] {
  override def outboundCommand(cmd: Command): Unit = cmd match {
    case Connected => stageStartup()
    case Shutdown  => stageShutdown()
    case Error(e)  => logger.error("Unhandled outbound error", e)
    case _         =>   // NOOP
  }
}
