package org.http4s.blaze.channel.nio1

import org.http4s.blaze.util

import scala.util.control.{ControlThrowable, NonFatal}
import java.nio.channels._
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.{Executor, RejectedExecutionException}

import org.http4s.blaze.util.TaskQueue
import org.log4s.getLogger

import scala.concurrent.ExecutionContext

/** A special thread that listens for events on the provided selector.
  *
  * @param id The name of this `SelectorLoop`
  * @param selector the `Selector` to listen on
  * @param bufferSize size of the scratch buffer instantiated for this thread
  *
  * @note when the `SelectorLoop` is closed all registered [[Selectable]]s
  *       are closed with it.
  */
final class SelectorLoop(
    id: String,
    selector: Selector,
    bufferSize: Int
) extends Thread(id)
    with Executor
    with ExecutionContext {

  private[this] val logger = getLogger
  private[this] val taskQueue = new TaskQueue

  @volatile
  private[this] var _isClosed = false

  /** Signal to the [[SelectorLoop]] that it should close */
  def close(): Unit = {
    _isClosed = true
    logger.info(s"Shutting down SelectorLoop ${getName()}")
    selector.wakeup()
    ()
  }

  /** Schedule the provided `Runnable` for execution, potentially running it now
    *
    * The provided task may be executed *now* if the calling thread is `this`
    * `SelectorLoop`, otherwise it is added to the task queue to be executed by
    * the `SelectorLoop` thread later.
    */
  @inline
  @throws[RejectedExecutionException]
  def executeTask(runnable: Runnable): Unit =
    if (Thread.currentThread() != this) enqueueTask(runnable)
    else {
      try runnable.run()
      catch { case NonFatal(t) => reportFailure(t) }
    }

  /** Schedule to provided `Runnable` for execution later
    *
    * The task will be added to the end of the queue of tasks scheduled
    * for execution regardless of where this method is called.
    *
    * @see `executeTask` for a method that will execute the task now if
    *     the calling thread is `this` `SelectorLoop`, or schedule it for
    *     later otherwise.
    */
  @throws[RejectedExecutionException]
  def enqueueTask(runnable: Runnable): Unit =
    taskQueue.enqueueTask(runnable) match {
      case TaskQueue.Enqueued =>
        () // nop

      case TaskQueue.FirstEnqueued =>
        selector.wakeup()

      case TaskQueue.Closed =>
        throw new RejectedExecutionException("This SelectorLoop is closed.")
    }

  @throws[RejectedExecutionException]
  override def execute(runnable: Runnable): Unit = enqueueTask(runnable)

  override def reportFailure(cause: Throwable): Unit =
    logger.info(cause)(s"Exception executing task in selector look $id")

  /** Initialize a new `Selectable` channel
    *
    * The `SelectableChannel` is added to the selector loop the
    * [[Selectable]] will be notified when it has events ready.
    *
    * @note the underlying `SelectableChannel` _must_ be
    *       configured in non-blocking mode.
    */
  def initChannel(
      ch: NIO1Channel,
      mkStage: SelectionKey => Selectable
  ): Unit =
    enqueueTask(new Runnable {
      def run(): Unit = {
        if (!selector.isOpen) ch.close()
        else try {
          // We place all this noise in the `try` since pretty
          // much every method on the `SelectableChannel` can throw.
          require(!ch.selectableChannel.isBlocking, s"Can only register non-blocking channels")
          val key = ch.selectableChannel.register(selector, 0)
          val head = mkStage(key)
          key.attach(head)
          logger.debug("Channel initialized.")
        } catch {
          case t @ (NonFatal(_) | _: ControlThrowable) =>
            logger.error(t)("Caught error during channel init.")
            ch.close()
        }
      }
    })

  // Main thread method. The loop will break if the Selector loop is closed
  override def run(): Unit = {
    // The scratch buffer is a direct buffer as this will often be used for I/O
    val scratch = ByteBuffer.allocateDirect(bufferSize)

    try while (!_isClosed) {
      // Run any pending tasks. These may set interest ops,
      // just compute something, etc.
      taskQueue.executeTasks()

      // Block here until some IO event happens or someone adds
      // a task to run and wakes the loop
      if (selector.select() > 0) {
        // We have some new IO operations waiting for us. Process them
        val it = selector.selectedKeys().iterator()
        processKeys(scratch, it)
      }

    } catch {
      case e: ClosedSelectorException =>
        _isClosed = true
        logger.error(e)("Selector unexpectedly closed")

      case e: Throwable =>
        logger.error(e)("Unhandled exception in selector loop")
        _isClosed = true
    }

    // If we've made it to here, we've exited the run loop and we need to shut down.
    // We first kill the selector and then drain any remaining tasks since closing
    // attached `Selectable`s may have resulted in more tasks.
    killSelector()
    taskQueue.close()
  }

  private[this] def processKeys(scratch: ByteBuffer, it: java.util.Iterator[SelectionKey]): Unit =
    while (it.hasNext) {
      val k = it.next()
      it.remove()

      val selectable = getAttachment(k)
      try {
        if (k.isValid) {
          if (selectable != null) {
            selectable.opsReady(scratch)
          } else {
            k.cancel()
            logger.error("Illegal state: selector key had null attachment.")
          }
        } else if (selectable != null) {
          selectable.close()
        }
      } catch {
        case t @ (NonFatal(_) | _: ControlThrowable) =>
          logger.error(t)("Error performing channel operations. Closing channel.")
          try selectable.closeWithError(t)
          catch {
            case t: Throwable =>
              logger.error(t)("Fatal error shutting down pipeline")
          }
      }
    }

  private[this] def killSelector(): Unit = {
    import scala.collection.JavaConverters._
    // We first close all the associated keys and then close
    // the `Selector` which will then be essentially useless.
    try {
      if (!selector.keys.isEmpty) {
        val ex = new ShutdownChannelGroupException
        selector.keys.asScala.foreach { k =>
          try {
            val head = getAttachment(k)
            if (head != null) {
              head.closeWithError(ex)
            }
          } catch { case _: IOException => /* NOOP */ }
        }
      }
      selector.close()
    } catch {
      case t @ (NonFatal(_) | _: ControlThrowable) =>
        logger.warn(t)("Killing selector resulted in an exception")
    }
  }

  private[this] def getAttachment(key: SelectionKey): Selectable =
    key.attachment match {
      case null => null
      case s: Selectable => s
      case other =>
        val ex = util.bug(s"Unexpected type: ${other.getClass.getName}")
        logger.error(ex)(ex.getMessage)
        throw ex
    }
}
