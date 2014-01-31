package blaze.pipeline

import java.util.Date

import scala.concurrent.Future
import Command._
import com.typesafe.scalalogging.slf4j.Logging
import blaze.util.Execution.directec

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */


sealed trait Common extends Logging {

  def name: String

  protected def stageStartup(): Unit = logger.trace(s"Starting up at ${new Date}")
  protected def stageShutdown(): Unit = logger.trace(s"Shutting down at ${new Date}")

  def inboundCommand(cmd: Command): Unit = cmd match {
    case Connected => stageStartup()
    case Shutdown  => stageShutdown()
    case _         => // NOOP
  }
}

sealed trait Tail[I] extends Common {
  private[pipeline] var _prevStage: Head[I] = null

//  def inboundCommand(cmd: Command): Unit = cmd match {
//    case Connected => stageStartup()
//    case Shutdown  => stageShutdown()
//    case _         => // NOOP
//  }

  final def channelRead(size: Int = -1): Future[I] = {
    logger.debug(s"Stage ${getClass.getName} sending read request.")

    try _prevStage.readRequest(size)
    catch { case t: Throwable => Future.failed(t) }
  }

    final def channelWrite(data: I): Future[Any] = {
      logger.debug(s"Stage ${getClass.getName} sending write request.")

      if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot write downstream")

      try _prevStage.writeRequest(data)
      catch { case t: Throwable => Future.failed(t) }
    }

  final def channelWrite(data: Seq[I]): Future[Any] = {
    logger.debug(s"Stage ${getClass.getName} sending multiple write request.")

    try _prevStage.writeRequest(data)
    catch { case t: Throwable => Future.failed(t) }
  }

  final def sendOutboundCommand(cmd: Command): Unit = {
    logger.debug(s"Stage ${getClass.getName} sending outbound command")

    try _prevStage.outboundCommand(cmd)
    catch { case t: Throwable => inboundCommand(Error(t)) }
  }

  final def findOutboundStage(name: String): Option[Common] = {
    if (this.name == name) Some(this)
    else _prevStage match {
      case s: Tail[_] => s.findOutboundStage(name)
      case t          => if (t.name == name) Some(t) else None

    }
  }

  final def findOutboundStage[C <: Common](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])

    else _prevStage match {
      case s: Tail[_] => s.findOutboundStage[C](clazz)
      case t =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }

  final def replaceInline(leafBuilder: LeafBuilder[I]): this.type = {
    replaceInline(leafBuilder.leaf)
  }

  final def replaceInline(stage: Tail[I]): this.type = {
    stageShutdown()

    stage._prevStage = this._prevStage
    this._prevStage._nextStage = stage

    // remove my links to other stages
    this._prevStage = null
    this match {
      case m: MidStage[_, _] => m._nextStage = null
      case _ => // NOOP
    }
    stage.inboundCommand(Command.Connected)
    this
  }
}

sealed trait Head[O] extends Common {

  // Overrides to propagate commands.
  override def inboundCommand(cmd: Command): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
  }

  def outboundCommand(cmd: Command): Unit = cmd match {
    case Connected => stageStartup()
    case Shutdown  => stageShutdown()
    case _         => // NOOP
  }

  private[pipeline] var _nextStage: Tail[O] = null

  def readRequest(size: Int): Future[O]

  def writeRequest(data: O): Future[Any]

  /** A simple default that serializes the write requests into the
    * single element form. It almost certainly should be overwritten
    * @param data sequence of elements which are to be written
    * @return Future which will resolve when pipeline is ready for more data or fails
    */
  def writeRequest(data: Seq[O]): Future[Any] = {
    data.foldLeft[Future[Any]](Future.successful()){ (f, d) =>
      f.flatMap(_ => writeRequest(d))(directec)
    }
  }

  final def sendInboundCommand(cmd: Command): Unit = {
    try _nextStage.inboundCommand(cmd)
    catch { case t: Throwable => outboundCommand(Error(t)) }
  }

  final def spliceAfter(stage: MidStage[O, O]): stage.type = {
    _nextStage._prevStage = stage
    _nextStage = stage
    stage
  }

    final def findInboundStage(name: String): Option[Common] = {
      if (this.name == name) Some(this)
      else _nextStage match {
        case s: MidStage[_, _] => s.findInboundStage(name)
        case t: TailStage[_]   => if (t.name == name) Some(t) else None
      }
    }

    final def findInboundStage[C <: Common](clazz: Class[C]): Option[C] = {
      if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
      else _nextStage match {
        case s: MidStage[_, _] => s.findInboundStage[C](clazz)
        case t: TailStage[_] =>
          if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
          else None
      }
    }
}


trait TailStage[I] extends Tail[I]

trait HeadStage[O] extends Head[O]

trait MidStage[I, O] extends Tail[I] with Head[O] {

  // Overrides to propagate commands.
  override def outboundCommand(cmd: Command): Unit = {
    super.outboundCommand(cmd)
    sendOutboundCommand(cmd)
  }

  final def replaceInline(stage: MidStage[I, O]): this.type = {
    stageShutdown()
    _nextStage._prevStage = stage
    if (!this.isInstanceOf[HeadStage[_]]) _prevStage._nextStage = stage
    this._nextStage = null
    this._prevStage = null
    this
  }

  final def replaceNext(stage: Tail[O]): Tail[O] = {
    _nextStage.replaceInline(stage)
  }

  final def removeStage(implicit ev: MidStage[I,O] =:= MidStage[I, I]): this.type = {
    stageShutdown()

    val me: MidStage[I, I] = ev(this)
    _prevStage._nextStage = me._nextStage
    me._nextStage._prevStage = me._prevStage

    _nextStage = null
    _prevStage = null
    this
  }
}



//sealed trait BaseStage[I] extends Logging {
//
//  def name: String
//
//  private[pipeline] var _prevStage: MidStage[_, I] = null
//
//  protected def stageStartup(): Unit = logger.trace(s"Starting up at ${new Date}")
//  protected def stageShutdown(): Unit = logger.trace(s"Shutting down at ${new Date}")
//
//  final def channelRead(size: Int = -1): Future[I] = {
//    logger.debug(s"Stage ${getClass.getName} sending read request.")
//
//    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot request read")
//
//    try _prevStage.readRequest(size)
//    catch { case t: Throwable => Future.failed(t) }
//  }
//
//  final def channelWrite(data: Seq[I]): Future[Any] = {
//    logger.debug(s"Stage ${getClass.getName} sending multiple write request.")
//
//    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot write downstream")
//
//    try _prevStage.writeRequest(data)
//    catch { case t: Throwable => Future.failed(t) }
//  }
//
//  final def channelWrite(data: I): Future[Any] = {
//    logger.debug(s"Stage ${getClass.getName} sending write request.")
//
//    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot write downstream")
//
//    try _prevStage.writeRequest(data)
//    catch { case t: Throwable => Future.failed(t) }
//  }
//
//  final def sendOutboundCommand(cmd: Command): Unit = {
//    logger.debug(s"Stage ${getClass.getName} sending outbound command")
//
//    if(this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage cannot send outbound commands!")
//
//    try _prevStage.outboundCommand(cmd)
//    catch { case t: Throwable => inboundCommand(Error(t)) }
//  }
//
//  def inboundCommand(cmd: Command): Unit = {
//    cmd match {
//      case Connected => stageStartup()
//      case Shutdown  => stageShutdown()
//      case _         => // NOOP
//    }
//  }
//
//  //////////////////////////////////////////////////////////////////////////////
//  final def replaceInline(leafBuilder: LeafBuilder[I]): this.type = {
//    replaceInline(leafBuilder.leaf)
//  }
//
//  final def replaceInline(stage: BaseStage[I]): this.type = {
//    stageShutdown()
//
//    stage._prevStage = this._prevStage
//    if (!this.isInstanceOf[HeadStage[_]]) _prevStage._nextStage = stage
//
//    // remove my links to other stages
//    this._prevStage = null
//    this match {
//      case m: MidStage[_, _] => m._nextStage = null
//      case _ => // NOOP
//    }
//    stage.inboundCommand(Command.Connected)
//    this
//  }
//
//  final def findOutboundStage(name: String): Option[BaseStage[_]] = {
//    if (this.name == name) Some(this)
//    else _prevStage match {
//      case t: HeadStage[_]   => if (t.name == name) Some(t) else None
//      case s: MidStage[_, _] => s.findOutboundStage(name)
//    }
//  }
//
//  final def findOutboundStage[C <: MidStage[_, _]](clazz: Class[C]): Option[C] = {
//    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
//    else _prevStage match {
//      case t: HeadStage[_] =>
//        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
//        else None
//
//      case s: MidStage[_, _] => s.findOutboundStage[C](clazz)
//      case _ => sys.error("Shouldn't get here.")
//    }
//  }
//
//  override def toString: String = {
//    s"Pipeline Stage ${getClass.getName}"
//  }
//}
//
///** This trait exists only to diverge the class structure
//  * to enforce certain methods will not accept a MidStage or HeadStage.
//  * @tparam O value which this stage can read and write from the pipeline
//  */
//trait TailStage[O] extends BaseStage[O]
//
//trait MidStage[I, O] extends _MidStage[I, O]
//
//sealed trait _MidStage[I, O] extends BaseStage[I] {
//
//  private[pipeline] var _nextStage: BaseStage[O] = null
//
//  def readRequest(size: Int): Future[O]
//
//  def writeRequest(data: O): Future[Any]
//
//  /** A simple default that serializes the write requests into the
//    * single element form. It almost certainly should be overwritten
//    * @param data sequence of elements which are to be written
//    * @return Future which will resolve when pipeline is ready for more data or fails
//    */
//  def writeRequest(data: Seq[O]): Future[Any] = {
//    data.foldLeft[Future[Any]](Future.successful()){ (f, d) =>
//      f.flatMap(_ => writeRequest(d))(directec)
//    }
//  }
//
//  override def inboundCommand(cmd: Command): Unit = {
//    super.inboundCommand(cmd)
//    sendInboundCommand(cmd)
//  }
//
//  def outboundCommand(cmd: Command): Unit = {
//    cmd match {
//      case Connected => stageStartup()
//      case Shutdown  => stageShutdown()
//      case _         => // NOOP
//    }
//    sendOutboundCommand(cmd)
//  }
//
//  final def sendInboundCommand(cmd: Command): Unit = {
//    try _nextStage.inboundCommand(cmd)
//    catch { case t: Throwable => outboundCommand(Error(t)) }
//  }
//
//  //////////////////////////////////////////////////////////////////////////////
//
//  final def replaceInline(stage: MidStage[I, O]): this.type = {
//    stageShutdown()
//    _nextStage._prevStage = stage
//    if (!this.isInstanceOf[HeadStage[_]]) _prevStage._nextStage = stage
//    this._nextStage = null
//    this._prevStage = null
//    this
//  }
//
//  final def replaceNext(stage: BaseStage[O]): BaseStage[O] = {
//    _nextStage.replaceInline(stage)
//  }
//
//  final def spliceAfter(stage: MidStage[O, O]): stage.type = {
//    _nextStage._prevStage = stage
//    _nextStage = stage
//    stage
//  }
//
//  final def removeStage(implicit ev: _MidStage[I,O]=:=_MidStage[I, I]): this.type = {
//    stageShutdown()
//    if (this.isInstanceOf[HeadStage[_]]) sys.error("HeadStage be removed!")
//
//    val me = ev(this)
//    _prevStage._nextStage = me._nextStage
//    me._nextStage._prevStage = me._prevStage
//
//    _nextStage = null
//    _prevStage = null
//    this
//  }
//
//  final def findInboundStage(name: String): Option[BaseStage[_]] = {
//    if (this.name == name) Some(this)
//    else _nextStage match {
//      case s: MidStage[_, _] => s.findInboundStage(name)
//      case t: BaseStage[_]   => if (t.name == name) Some(t) else None
//    }
//  }
//
//  final def findInboundStage[C <: BaseStage[_]](clazz: Class[C]): Option[C] = {
//    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
//    else _nextStage match {
//      case s: MidStage[_, _] => s.findInboundStage[C](clazz)
//      case t: BaseStage[_] =>
//        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
//        else None
//    }
//  }
//
//  final def getLastStage: TailStage[_] = _nextStage match {
//    case s: MidStage[_, _] if s._nextStage != null => s.getLastStage
//    case n: TailStage[_]                     => n
//    case _ => sys.error("Cannot get last stage. Next in line: " + _nextStage)
//  }
//}
//
///** HeadStage represents the front of the pipeline, interacting with whatever
//  * external data source dictated to service its tail nodes.
//  * WARNING! Do not attempt to use the channelWrite or channelRead, as they attempt
//  * to access an upstream stage, which doesn't exist for a head node
//  *
//  * @tparam O type of data which this stage can submit
//  */
//trait HeadStage[O] extends MidStage[Nothing, O] {
//  override def outboundCommand(cmd: Command): Unit = cmd match {
//    case Connected => stageStartup()
//    case Shutdown  => stageShutdown()
//    case Error(e)  => logger.error("Unhandled outbound error", e)
//    case _         =>   // NOOP
//  }
//}
