package org.http4s.blaze.channel.nio1


import org.http4s.blaze.pipeline.{Command => Cmd}
import org.http4s.blaze.pipeline._
import org.http4s.blaze.channel.BufferPipelineBuilder

import scala.util.control.{ControlThrowable, NonFatal}
import java.nio.channels._
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.{Executor, RejectedExecutionException}

import org.http4s.blaze.util.TaskQueue
import org.log4s.getLogger

import scala.concurrent.ExecutionContext

/** A special thread that listens for events on the provided selector
  *
  * @param id The name of this `SelectorLoop`
  * @param selector the `Selector` to listen on
  * @param bufferSize size of the scratch buffer instantiated for this thread
  */
final class SelectorLoop(
  id: String,
  selector: Selector,
  bufferSize: Int
) extends Thread(id) with Executor with ExecutionContext {

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
  def executeTask(runnable: Runnable): Unit =  {
    if (Thread.currentThread() != this) enqueueTask(runnable)
    else {
      try runnable.run()
      catch { case NonFatal(t) => reportFailure(t) }
    }
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
  def enqueueTask(runnable: Runnable): Unit = {
    taskQueue.enqueueTask(runnable) match {
      case TaskQueue.Enqueued =>
        ()// nop

      case TaskQueue.FirstEnqueued =>
        selector.wakeup()

      case TaskQueue.Closed =>
        throw new RejectedExecutionException("This SelectorLoop is closed.")
    }
  }

  override def execute(runnable: Runnable): Unit = enqueueTask(runnable)

  override def reportFailure(cause: Throwable): Unit = {
    logger.info(cause)(s"Exception executing task in selector look $id")
  }

  /** Initialize a new `Selectable` channel
    *
    * The `SelectableChannel` is added to the selector loop and
    * the pipeline is constructed using the resultant `SelectionKey`
    */
  def initChannel(
    builder: BufferPipelineBuilder,
    ch: SelectableChannel,
    mkStage: SelectionKey => NIO1HeadStage
  ): Unit = {
    enqueueTask( new Runnable {
      def run(): Unit = {
        ch.configureBlocking(false)
        val key = ch.register(selector, 0)
        try {
          val head = mkStage(key)
          key.attach(head)
          // construct the pipeline
          builder(NIO1Connection(ch)).base(head)
          head.inboundCommand(Command.Connected)
          logger.debug("Started channel.")
        } catch {
          case t @ (NonFatal(_) | _: ControlThrowable) =>
            logger.error(t)("Caught error during channel init.")
            key.attach(null)
            key.cancel()
            ch.close()
        }
      }
    })
  }

  // Main thread method. The loop will break if the Selector loop is closed
  override def run(): Unit = {
    // The scratch buffer is a direct buffer as this will often be used for I/O
    val scratch = ByteBuffer.allocateDirect(bufferSize)

    try while(!_isClosed) {
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

    // If we've made it to here, we've exited the run loop and we need to shut down
    killSelector()
    taskQueue.close()
  }

  private[this] def processKeys(scratch: ByteBuffer, it: java.util.Iterator[SelectionKey]): Unit = {
    while(it.hasNext) {
      val k = it.next()
      it.remove()

      try if (k.isValid) {
        val head = k.attachment.asInstanceOf[NIO1HeadStage]
        if (head != null) {
          head.opsReady(scratch)
        } else {
          // Null head. Must be disconnected
          k.cancel()
          logger.error("Illegal state: selector key had null attachment.")
        }
      } catch {
        case t: Throwable =>
          logger.error(t) {
            if (t.isInstanceOf[IOException]) "IOException while performing channel operations. Closing channel."
            else "Error performing channel operations. Closing channel."
          }

          try {
            val head = k.attachment.asInstanceOf[NIO1HeadStage]
            head.closeWithError(t)
          } catch {
            case t @ (NonFatal(_) | _: ControlThrowable) =>
              logger.error(t)("Fatal error shutting down pipeline")
          }
          k.attach(null)
          k.cancel()
      }
    }
  }

  private[this] def killSelector(): Unit = {
    import scala.collection.JavaConverters._
    try {
      selector.keys.asScala.foreach { k =>
        try {
          val head = k.attachment
          if (head != null) {
            val stage = head.asInstanceOf[NIO1HeadStage]
            stage.sendInboundCommand(Cmd.Disconnected)
            stage.closeWithError(Cmd.EOF)
          }
          k.channel.close()
          k.attach(null)
        } catch { case _: IOException => /* NOOP */ }
      }

      selector.close()
    } catch {
      case t @ (NonFatal(_) | _: ControlThrowable) =>
        logger.warn(t)("Killing selector resulted in an exception")
    }
  }
}
