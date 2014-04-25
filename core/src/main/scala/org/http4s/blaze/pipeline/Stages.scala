package org.http4s.blaze.pipeline

import java.util.Date

import scala.concurrent.{Promise, Future}
import Command._
import org.http4s.blaze.util.Logging
import org.http4s.blaze.util.Execution.directec
import scala.concurrent.duration.Duration
import org.http4s.blaze.util.Execution
import java.util.concurrent.TimeoutException

/*
 * @author Bryce Anderson
 *         Created on 1/4/14
 *
 *         Stages are formed from the three fundamental types. They essentially form a
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

sealed trait Stage extends Logging {

  def name: String

  protected def stageStartup(): Unit = logger.trace(s"Starting up at ${new Date}")
  protected def stageShutdown(): Unit = logger.trace(s"Shutting down at ${new Date}")

  /** Handle basic startup and shutdown commands.
    * This should clearly be overridden in all cases except possibly TailStages
    *
    * @param cmd a command originating from the channel
    */
  def inboundCommand(cmd: Command): Unit = cmd match {
    case Connect => stageStartup()
    case Disconnect  => stageShutdown()
    case _         => // NOOP
  }
}

sealed trait Tail[I] extends Stage {
  
  private[pipeline] var _prevStage: Head[I] = null

  final def channelRead(size: Int = -1, timeout: Duration = Duration.Inf): Future[I] = {
    try {
      if (_prevStage != null) {
        val f = _prevStage.readRequest(size)
        if (timeout.isFinite()) {
          val p = Promise[I]

          scheduleTimeout(p, f, timeout)
          p.future
        }
        else f
      } else stageDisconnected
    }  catch { case t: Throwable => return Future.failed(t) }
  }

  private def stageDisconnected: Future[Nothing] = {
    Future.failed(new Exception(s"This stage '${this}' isn't connected!"))
  }

  final def channelWrite(data: I): Future[Unit] = channelWrite(data, Duration.Inf)

  final def channelWrite(data: I, timeout: Duration): Future[Unit] = {
    logger.debug(s"Stage ${getClass.getName} sending write request with timeout $timeout")
    try {
      if (_prevStage != null) {
        val f = _prevStage.writeRequest(data)
        if (timeout.isFinite()) {
          val p = Promise[Unit]
          scheduleTimeout(p, f, timeout)
          p.future
        }
        else f
      } else stageDisconnected
    }
    catch { case t: Throwable => Future.failed(t) }
  }

  final def channelWrite(data: Seq[I]): Future[Unit] = channelWrite(data, Duration.Inf)

  final def channelWrite(data: Seq[I], timeout: Duration): Future[Unit] = {
    logger.debug(s"Stage ${getClass.getName} sending multiple write request with timeout $timeout")
    try {
      if (_prevStage != null) {
        val f = _prevStage.writeRequest(data)

        if (timeout.isFinite()) {
          val p = Promise[Unit]
          scheduleTimeout(p, f, timeout)
          p.future
        }
        else f
      } else stageDisconnected
    } catch { case t: Throwable => Future.failed(t) }

  }

  final def sendOutboundCommand(cmd: Command): Unit = {
    logger.debug(s"Stage ${getClass.getName} sending outbound command")
    if (_prevStage != null) {
      try _prevStage.outboundCommand(cmd)
      catch { case t: Throwable => inboundCommand(Error(t)) }
    } else {
      val e = new Exception("cannot send outbound command on disconnected stage")
      logger.error("", e)
      throw e
    }

  }

  final def findOutboundStage(name: String): Option[Stage] = {
    if (this.name == name) Some(this)
    else if (_prevStage == null) None
    else _prevStage match {
      case s: Tail[_] => s.findOutboundStage(name)
      case t          => if (t.name == name) Some(t) else None

    }
  }

  final def findOutboundStage[C <: Stage](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else if (_prevStage == null) None
    else _prevStage match {
      case s: Tail[_] => s.findOutboundStage[C](clazz)
      case t =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }

  final def replaceInline(leafBuilder: LeafBuilder[I], startup: Boolean = true): this.type = {
    stageShutdown()

    if (this._prevStage == null) return this

    this match {
      case m: MidStage[_, _] =>
        m.sendInboundCommand(Command.Disconnect)
        m._nextStage = null

      case _ => // NOOP
    }

    val prev = this._prevStage
    this._prevStage._nextStage = null
    this._prevStage = null

    prev match {
      case m: MidStage[_, I] => leafBuilder.prepend(m)
      case h: HeadStage[I] => leafBuilder.base(h)
    }

    if (startup) prev.sendInboundCommand(Command.Connect)

    this
  }

  ///////////////////////////////////////////////////////////////////
  /** Schedules a timeout and sets it to race against the provided future
    * @param p Promise[T] to be completed by whichever comes first:
    *          the timeout or resolution of the Future[T] f
    * @param f Future the timeout is racing against
    * @param timeout time from now which is considered a timeout
    * @tparam T type of result expected
    */
  private def scheduleTimeout[T](p: Promise[T], f: Future[T], timeout: Duration) {
    val r = new Runnable {
      def run() {
        val a = new TimeoutException("Read request timed out")
        p.tryFailure(a)
      }
    }

    val ecf = Execution.scheduler.schedule(r, timeout)

    f.onComplete { t =>
      ecf.cancel()
      p.tryComplete(t)
    }(Execution.directec)
  }
}

sealed trait Head[O] extends Stage {

  private[pipeline] var _nextStage: Tail[O] = null

  def readRequest(size: Int): Future[O]

  def writeRequest(data: O): Future[Unit]

  /** A simple default that serializes the write requests into the
    * single element form. It almost certainly should be overwritten
    * @param data sequence of elements which are to be written
    * @return Future which will resolve when pipeline is ready for more data or fails
    */
  def writeRequest(data: Seq[O]): Future[Unit] = {
    data.foldLeft[Future[Unit]](Future.successful(())){ (f, d) =>
      f.flatMap(_ => writeRequest(d))(directec)
    }
  }

  final def sendInboundCommand(cmd: Command): Unit = {
    if (_nextStage != null) {
      try _nextStage.inboundCommand(cmd)
      catch { case t: Throwable => outboundCommand(Error(t)) }
    } else {
      val e = new Exception("cannot send inbound command on disconnected stage")
      logger.error("", e)
      throw e
    }
  }

  // Overrides to propagate commands.
  override def inboundCommand(cmd: Command): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
  }

  def outboundCommand(cmd: Command): Unit = cmd match {
    case Connect => stageStartup()
    case Disconnect  => stageShutdown()
    case _         => // NOOP
  }

  final def spliceAfter(stage: MidStage[O, O]): stage.type = {
    if (_nextStage != null) {
      _nextStage._prevStage = stage
      _nextStage = stage
      stage
    } else {
      val e = new Exception("cannot send outbound command on disconnected stage")
      logger.error("", e)
      throw e
    }
  }

  final def findInboundStage(name: String): Option[Stage] = {
    if (this.name == name) Some(this)
    else if (_nextStage == null) None
    else _nextStage match {
      case s: MidStage[_, _] => s.findInboundStage(name)
      case t: TailStage[_]   => if (t.name == name) Some(t) else None
    }
  }

  final def findInboundStage[C <: Stage](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else if (_nextStage == null) None
    else _nextStage match {
      case s: MidStage[_, _] => s.findInboundStage[C](clazz)
      case t: TailStage[_] =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }
}

/** The three fundamental stage types */
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

    if (_nextStage == null || _prevStage == null) return this

    _nextStage._prevStage = stage
    _prevStage._nextStage = stage

    this._nextStage = null
    this._prevStage = null
    this
  }

  final def replaceNext(stage: LeafBuilder[O]): Tail[O] = _nextStage.replaceInline(stage)

  final def removeStage(implicit ev: MidStage[I,O] =:= MidStage[I, I]): this.type = {
    stageShutdown()

    if (_prevStage == null || _nextStage == null) return this

    val me: MidStage[I, I] = ev(this)
    _prevStage._nextStage = me._nextStage
    me._nextStage._prevStage = me._prevStage

    _nextStage = null
    _prevStage = null
    this
  }
}
