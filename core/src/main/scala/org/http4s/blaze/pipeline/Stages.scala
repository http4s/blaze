package org.http4s.blaze.pipeline

import java.util.concurrent.TimeoutException

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration
import org.log4s.getLogger
import Command._
import org.http4s.blaze.util.Execution.directec
import org.http4s.blaze.util.Execution

import scala.util.control.NonFatal

/*         Stages are formed from the three fundamental types. They essentially form a
 *         double linked list. Commands can be sent in both directions while data is only
 *         requested from the end of the list, or Tail sections and returned as Futures
 *         which can get transformed down the stages.
 *
 *                    Class Structure
 *                 -------------------
 *                        Stage                       // Root trait
 *                       /     \
 *                  Head[O]   Tail[I]                 // Linked List traits
 *                 /      \  /       \
 *     HeadStage[O]   MidStage[I,O]   TailStage[I]    // Fundamental stage traits
 *
 *       -------------- Inbound -------------->
 *       < ------------ Outbound --------------
 *
 *
 */

sealed trait Stage {
  final protected val logger = getLogger(this.getClass)

  def name: String

  /** Start the stage, allocating resources etc.
    *
    * This method should not effect other stages by sending commands etc unless it creates them.
    * It is not impossible that the stage will receive other commands besides [[Connected]]
    * before this method is called. It is not impossible for this method to be called multiple
    * times by misbehaving stages. It is therefore recommended that the method be idempotent.
    */
  protected def stageStartup(): Unit =
    logger.debug(s"Starting up.")

  /** Shuts down the stage, deallocating resources, etc.
    *
    * This method will be called when the stages receives a [[Disconnect]] command unless the
    * `inboundCommand` method is overridden. It is not impossible that this will not be called
    * due to failure for other stages to propagate shutdown commands. Conversely, it is also
    * possible for this to be called more than once due to the reception of multiple disconnect
    * commands. It is therefore recommended that the method be idempotent.
    */
  protected def stageShutdown(): Unit =
    logger.debug(s"Shutting down.")

  /** Handle basic startup and shutdown commands.
    * This should clearly be overridden in all cases except possibly TailStages
    *
    * @param cmd a command originating from the channel
    */
  def inboundCommand(cmd: InboundCommand): Unit = cmd match {
    case Connected => stageStartup()
    case _ => logger.warn(s"$name received unhandled inbound command: $cmd")
  }
}

sealed trait Tail[I] extends Stage {
  private[pipeline] var _prevStage: Head[I] = null

  def channelRead(size: Int = -1, timeout: Duration = Duration.Inf): Future[I] =
    try {
      if (_prevStage != null) {
        val f = _prevStage.readRequest(size)
        checkTimeout(timeout, f)
      } else _stageDisconnected()
    } catch { case NonFatal(t) => Future.failed(t) }

  /** Write a single outbound message to the pipeline */
  def channelWrite(data: I): Future[Unit] =
    if (_prevStage != null) {
      try _prevStage.writeRequest(data)
      catch { case t: Throwable => Future.failed(t) }
    } else _stageDisconnected()

  /** Write a single outbound message to the pipeline with a timeout */
  final def channelWrite(data: I, timeout: Duration): Future[Unit] = {
    val f = channelWrite(data)
    checkTimeout(timeout, f)
  }

  /** Write a collection of outbound messages to the pipeline */
  def channelWrite(data: Seq[I]): Future[Unit] =
    if (_prevStage != null) {
      try _prevStage.writeRequest(data)
      catch { case t: Throwable => Future.failed(t) }
    } else _stageDisconnected()

  /** Write a collection of outbound messages to the pipeline with a timeout */
  final def channelWrite(data: Seq[I], timeout: Duration): Future[Unit] = {
    val f = channelWrite(data)
    checkTimeout(timeout, f)
  }

  /** Insert the `MidStage` before this `Stage` */
  final def spliceBefore(stage: MidStage[I, I]): Unit =
    if (_prevStage != null) {
      stage._prevStage = _prevStage
      stage._nextStage = this
      _prevStage._nextStage = stage
      _prevStage = stage
    } else {
      val e = new Exception("Cannot splice stage before a disconnected stage")
      logger.error(e)("")
      throw e
    }

  /** Send a command to the next outbound `Stage` of the pipeline */
  final def sendOutboundCommand(cmd: OutboundCommand): Unit = {
    logger.debug(s"Stage ${getClass.getSimpleName} sending outbound command: $cmd")
    if (_prevStage != null) {
      try _prevStage.outboundCommand(cmd)
      catch {
        case t: Throwable => logger.error(t)("Outbound command caused an error")
      }
    } else {
      val e = new Exception("Cannot send outbound command on disconnected stage")
      logger.error(e)("")
      throw e
    }
  }

  /** Find the next outbound `Stage` with the given name, if it exists. */
  final def findOutboundStage(name: String): Option[Stage] =
    if (this.name == name) Some(this)
    else if (_prevStage == null) None
    else
      _prevStage match {
        case s: Tail[_] => s.findOutboundStage(name)
        case t => if (t.name == name) Some(t) else None

      }

  /** Find the next outbound `Stage` of type `C`, if it exists. */
  final def findOutboundStage[C <: Stage](clazz: Class[C]): Option[C] =
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else if (_prevStage == null) None
    else
      _prevStage match {
        case s: Tail[_] => s.findOutboundStage[C](clazz)
        case t =>
          if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
          else None
      }

  /** Replace all downstream `Stage`s, including this `Stage`.
    *
    * If this was a `MidStage`, its inbound element is notified via a `Disconnected` Command.
    */
  final def replaceTail(leafBuilder: LeafBuilder[I], startup: Boolean): this.type = {
    stageShutdown()

    if (this._prevStage != null) {
      this match {
        case m: MidStage[_, _] =>
          m.sendInboundCommand(Disconnected)
          m._nextStage = null

        case _ => // NOOP, must be a TailStage
      }

      val prev = this._prevStage
      this._prevStage._nextStage = null
      this._prevStage = null

      prev match {
        case m: MidStage[_, I] => leafBuilder.prepend(m)
        case h: HeadStage[I] => leafBuilder.base(h)
      }

      if (startup) prev.sendInboundCommand(Command.Connected)
    }
    this
  }

  /** Arranges a timeout for a write request */
  private def checkTimeout[T](timeout: Duration, f: Future[T]): Future[T] =
    if (timeout.isFinite()) {
      val p = Promise[T]
      scheduleTimeout(p, f, timeout)
      p.future
    } else f

  ///////////////////////////////////////////////////////////////////
  /** Schedules a timeout and sets it to race against the provided future
    * @param p Promise[T] to be completed by whichever comes first:
    *          the timeout or resolution of the Future[T] f
    * @param f Future the timeout is racing against
    * @param timeout time from now which is considered a timeout
    * @tparam T type of result expected
    */
  private def scheduleTimeout[T](p: Promise[T], f: Future[T], timeout: Duration): Unit = {
    val r = new Runnable {
      def run(): Unit = {
        val a = new TimeoutException("Read request timed out")
        p.tryFailure(a)
        ()
      }
    }

    val ecf = Execution.scheduler.schedule(r, timeout)

    f.onComplete { t =>
      ecf.cancel()
      p.tryComplete(t)
    }(Execution.directec)
  }

  private def _stageDisconnected(): Future[Nothing] =
    Future.failed(new Exception(s"This stage '$name' isn't connected!"))
}

sealed trait Head[O] extends Stage {
  private[pipeline] var _nextStage: Tail[O] = null

  /** Called by the next inbound `Stage` to signal interest in reading data.
    *
    * @param size Hint as to the size of the message intended to be read. May not be meaningful or honored.
    * @return     `Future` that will resolve with the requested inbound data, or an error.
    */
  def readRequest(size: Int): Future[O]

  /** Data that the next inbound `Stage` wants to send outbound.
    *
    * @return a `Future` that resolves when the data has been handled.
    */
  def writeRequest(data: O): Future[Unit]

  /** Collection of data that the next inbound `Stage` wants to sent outbound.
    *
    * It is generally assumed that the order of elements has meaning.
    * @return a `Future` that resolves when the data has been handled.
    */
  def writeRequest(data: Seq[O]): Future[Unit] =
    data.foldLeft[Future[Unit]](Future.successful(())) { (f, d) =>
      f.flatMap(_ => writeRequest(d))(directec)
    }

  /** Replace all remaining inbound `Stage`s of the pipeline, not including this `Stage`. */
  final def replaceNext(stage: LeafBuilder[O], startup: Boolean): Tail[O] =
    _nextStage.replaceTail(stage, startup)

  /** Send a command to the next inbound `Stage` of the pipeline */
  final def sendInboundCommand(cmd: InboundCommand): Unit = {
    logger.debug(s"Stage ${getClass.getSimpleName} sending inbound command: $cmd")
    if (_nextStage != null) {
      try _nextStage.inboundCommand(cmd)
      catch { case t: Throwable => outboundCommand(Error(t)) }
    } else {
      val e = new Exception("Cannot send inbound command on disconnected stage")
      logger.error(e)("")
      throw e
    }
  }

  /** Receives inbound commands
    * Override to capture commands. */
  override def inboundCommand(cmd: InboundCommand): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
  }

  /** Receives outbound commands
    * Override to capture commands. */
  def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
    case Disconnect => stageShutdown()
    case Error(e) => logger.error(e)(s"$name received unhandled error command")
    case cmd => logger.warn(s"$name received unhandled outbound command: $cmd")
  }

  /** Insert the `MidStage` after `this` */
  final def spliceAfter(stage: MidStage[O, O]): Unit =
    if (_nextStage != null) {
      stage._nextStage = _nextStage
      stage._prevStage = this
      _nextStage._prevStage = stage
      _nextStage = stage
    } else {
      val e = new Exception("Cannot splice stage after disconnected stage")
      logger.error(e)("")
      throw e
    }

  /** Find the next outbound `Stage` with the given name, if it exists. */
  final def findInboundStage(name: String): Option[Stage] =
    if (this.name == name) Some(this)
    else if (_nextStage == null) None
    else
      _nextStage match {
        case s: MidStage[_, _] => s.findInboundStage(name)
        case t: TailStage[_] => if (t.name == name) Some(t) else None
      }

  /** Find the next inbound `Stage` of type `C`, if it exists. */
  final def findInboundStage[C <: Stage](clazz: Class[C]): Option[C] =
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else if (_nextStage == null) None
    else
      _nextStage match {
        case s: MidStage[_, _] => s.findInboundStage[C](clazz)
        case t: TailStage[_] =>
          if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
          else None
      }
}

/** The three fundamental stage types */
trait TailStage[I] extends Tail[I]

trait HeadStage[O] extends Head[O]

trait MidStage[I, O] extends Tail[I] with Head[O] {

  // Overrides to propagate commands.
  override def outboundCommand(cmd: OutboundCommand): Unit = {
    super.outboundCommand(cmd)
    sendOutboundCommand(cmd)
  }

  /** Replace this `MidStage` with the provided `MidStage` of the same type */
  final def replaceInline(stage: MidStage[I, O]): this.type = {
    stageShutdown()

    if (_nextStage != null && _prevStage != null) {
      _nextStage._prevStage = stage
      _prevStage._nextStage = stage
      this._nextStage = null
      this._prevStage = null
    }

    this
  }

  /** Remove this `MidStage` from the pipeline */
  final def removeStage()(implicit ev: MidStage[I, O] =:= MidStage[I, I]): Unit = {
    stageShutdown()

    if (_prevStage != null && _nextStage != null) {
      val me: MidStage[I, I] = ev(this)
      _prevStage._nextStage = me._nextStage
      me._nextStage._prevStage = me._prevStage

      _nextStage = null
      _prevStage = null
    } else
      logger.warn(s"Cannot remove a disconnected stage ${this.getClass.getSimpleName}")
  }
}
