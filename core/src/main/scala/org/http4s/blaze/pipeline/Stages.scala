/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.pipeline

import java.util.concurrent.TimeoutException
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration
import org.log4s.getLogger
import Command._
import org.http4s.blaze.util.{Execution, FutureUnit}
import org.http4s.blaze.util.Execution.directec
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

  def closePipeline(cause: Option[Throwable]): Unit

  /** Start the stage, allocating resources etc.
    *
    * This method should not effect other stages by sending commands etc unless it creates them. It
    * is not impossible that the stage will receive other commands besides `Connected` before this
    * method is called. It is not impossible for this method to be called multiple times by
    * misbehaving stages. It is therefore recommended that the method be idempotent.
    */
  protected def stageStartup(): Unit =
    logger.debug(s"Starting up.")

  /** Shuts down the stage, deallocating resources, etc.
    *
    * It will be called when the stage receives a `Disconnected` command unless [[inboundCommand]]
    * is overridden. This method should not send or `Disconnected` commands.
    *
    * It is possible that this will not be called due to failure of other stages to propagate
    * shutdown commands. Conversely, it is also possible for this to be called more than once due to
    * the reception of multiple shutdown commands. It is therefore recommended that the method be
    * idempotent.
    */
  protected def stageShutdown(): Unit =
    logger.debug(s"Shutting down.")

  /** Handle basic startup and shutdown commands.
    *
    * @param cmd
    *   a command originating from the channel
    */
  def inboundCommand(cmd: InboundCommand): Unit =
    cmd match {
      case Connected => stageStartup()
      case Disconnected => stageShutdown()
      case _ => logger.warn(s"$name received unhandled inbound command: $cmd")
    }
}

sealed trait Tail[I] extends Stage {
  private[pipeline] var _prevStage: Head[I] = null

  final def closePipeline(cause: Option[Throwable]): Unit = {
    stageShutdown()
    val prev = _prevStage
    if (prev != null)
      prev.closePipeline(cause)
  }

  def channelRead(size: Int = -1, timeout: Duration = Duration.Inf): Future[I] =
    try
      if (_prevStage != null) {
        val f = _prevStage.readRequest(size)
        checkTimeout(timeout, f)
      } else _stageDisconnected()
    catch { case NonFatal(t) => Future.failed(t) }

  /** Write a single outbound message to the pipeline */
  def channelWrite(data: I): Future[Unit] =
    if (_prevStage != null)
      try _prevStage.writeRequest(data)
      catch { case NonFatal(t) => Future.failed(t) }
    else _stageDisconnected()

  /** Write a single outbound message to the pipeline with a timeout */
  final def channelWrite(data: I, timeout: Duration): Future[Unit] = {
    val f = channelWrite(data)
    checkTimeout(timeout, f)
  }

  /** Write a collection of outbound messages to the pipeline */
  def channelWrite(data: collection.Seq[I]): Future[Unit] =
    if (_prevStage != null)
      try _prevStage.writeRequest(data)
      catch { case NonFatal(t) => Future.failed(t) }
    else _stageDisconnected()

  /** Write a collection of outbound messages to the pipeline with a timeout */
  final def channelWrite(data: collection.Seq[I], timeout: Duration): Future[Unit] = {
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
      throw e
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
    if (timeout.isFinite) {
      val p = Promise[T]()
      scheduleTimeout(p, f, timeout)
      p.future
    } else f

  // /////////////////////////////////////////////////////////////////
  /** Schedules a timeout and sets it to race against the provided future
    * @param p
    *   Promise[T] to be completed by whichever comes first: the timeout or resolution of the
    *   Future[T] f
    * @param f
    *   Future the timeout is racing against
    * @param timeout
    *   time from now which is considered a timeout
    * @tparam T
    *   type of result expected
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
    * @param size
    *   Hint as to the size of the message intended to be read. May not be meaningful or honored.
    * @return
    *   `Future` that will resolve with the requested inbound data, or an error.
    */
  def readRequest(size: Int): Future[O]

  /** Data that the next inbound `Stage` wants to send outbound.
    *
    * @return
    *   a `Future` that resolves when the data has been handled.
    */
  def writeRequest(data: O): Future[Unit]

  /** Collection of data that the next inbound `Stage` wants to sent outbound.
    *
    * It is generally assumed that the order of elements has meaning.
    * @return
    *   a `Future` that resolves when the data has been handled.
    */
  def writeRequest(data: collection.Seq[O]): Future[Unit] =
    data.foldLeft[Future[Unit]](FutureUnit)((f, d) => f.flatMap(_ => writeRequest(d))(directec))

  /** Replace all remaining inbound `Stage`s of the pipeline, not including this `Stage`. */
  final def replaceNext(stage: LeafBuilder[O], startup: Boolean): Tail[O] =
    _nextStage.replaceTail(stage, startup)

  /** Send a command to the next inbound `Stage` of the pipeline */
  final def sendInboundCommand(cmd: InboundCommand): Unit = {
    logger.debug(s"Stage ${getClass.getSimpleName} sending inbound command: $cmd")
    val next = _nextStage
    if (next != null) {
      try next.inboundCommand(cmd)
      catch {
        case NonFatal(t) =>
          logger.error(t)("Exception caught when attempting inbound command")
          closePipeline(Some(t))
      }
    }
  }

  /** Receives inbound commands Override to capture commands.
    */
  override def inboundCommand(cmd: InboundCommand): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
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

trait HeadStage[O] extends Head[O] {

  /** Close the channel with an error
    *
    * @note
    *   EOF is a valid error to close the channel with and signals normal termination.
    */
  protected def doClosePipeline(cause: Option[Throwable]): Unit

  final def closePipeline(cause: Option[Throwable]): Unit = {
    stageShutdown()
    doClosePipeline(cause)
  }
}

trait MidStage[I, O] extends Tail[I] with Head[O] {

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
      logger.debug(s"Removed mid-stage: $name")
      val me: MidStage[I, I] = ev(this)
      _prevStage._nextStage = me._nextStage
      me._nextStage._prevStage = me._prevStage

      _nextStage = null
      _prevStage = null
    } else
      logger.debug(s"Attempted to remove a disconnected mid-stage: $name")
  }
}
