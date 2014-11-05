package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.{SSLException, SSLEngineResult, SSLEngine}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.{BufferTools, ScratchBuffer}
import org.log4s.getLogger
import scala.annotation.tailrec


class SSLStage(engine: SSLEngine, maxSubmission: Int = -1) extends MidStage[ByteBuffer, ByteBuffer] {

  import org.http4s.blaze.util.BufferTools._

  def name: String = s"SSLStage"

  private val maxNetSize = engine.getSession.getPacketBufferSize

  private val maxBuffer = math.max(maxNetSize, engine.getSession.getApplicationBufferSize)
  
  private var readLeftover: ByteBuffer = null

  def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]

    channelRead(size).onComplete{
      case Success(b) => readLoop(b, size, new ListBuffer[ByteBuffer], p)
      case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[ByteBuffer]])
    }(directec)
    p.future
  }

  private def sslHandshake(data: ByteBuffer, r: SSLEngineResult, f: (SSLEngineResult, ByteBuffer) => Any, ex: Throwable => Any): Unit = {
    val o = ScratchBuffer.getScratchBuffer(maxBuffer)

    r.getHandshakeStatus match {
      case HandshakeStatus.NEED_UNWRAP =>
        if (r.getStatus == Status.BUFFER_UNDERFLOW) {
          channelRead().onComplete {
            case Success(b) =>
              val sum = concatBuffers(data, b)
              try {
                val rr = engine.unwrap(sum, o)
                sslHandshake(sum, rr, f, ex)
              } catch {
                case t: SSLException =>
                  logger.warn(t)("SSL Error")
                  stageShutdown()
                  ex(t)
              }


            case Failure(t) => ex(t)
          }(trampoline)
          return
        }

        try {
          val rr = engine.unwrap(data, o)

          if (o.position() > 0) {
            logger.warn("Unwrapped and returned some data: " + o + "\n" + new String(o.array(), 0, o.position()))
          }

          sslHandshake(data, rr, f, ex)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSL Error")
            stageShutdown()
            ex(t)
        }


      case HandshakeStatus.NEED_TASK =>
        runTasks()
        try {
          val rr = engine.unwrap(data, o)  // just kind of bump it along
          sslHandshake(data, rr, f, ex)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSL Error")
            stageShutdown()
            ex(t)
        }

      case HandshakeStatus.NEED_WRAP =>
        try {
          val r = engine.wrap(BufferTools.emptyBuffer, o)
          assert(r.bytesProduced() > 0)
          o.flip()

          channelWrite(copyBuffer(o)).onComplete {
            case Success(_) => sslHandshake(data, r, f, ex)

            case Failure(t) => ex(t)
          }(trampoline)

          return
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSL Error")
            stageShutdown()
            ex(t)
        }

      case _ => f(r, data)
    }
  }

  // If we have at least one output buffer, we won't read more data until another request is made
  private def readLoop(buffer: ByteBuffer, size: Int, out: ListBuffer[ByteBuffer], p: Promise[ByteBuffer]): Unit = {

    // Consolidate buffers if they exist
    val b = concatBuffers(readLeftover, buffer)
    readLeftover = null

    var bytesRead = 0
    val o = ScratchBuffer.getScratchBuffer(maxBuffer)

    while(true) {
      try {
        val r = engine.unwrap(b, o)

        if (r.bytesProduced() > 0) {
          bytesRead += r.bytesProduced()
          o.flip()
          out += copyBuffer(o)
          o.clear()
        }

        logger.debug(s"SSL Read Request Status: $r, $o")

        r.getHandshakeStatus match {
          case HandshakeStatus.NOT_HANDSHAKING | HandshakeStatus.NOT_HANDSHAKING => // noop
          case _ => // must be handshaking.
            sslHandshake(emptyBuffer, r, (_, _) => readLoop(b, size - bytesRead, out, p),
              t => {stageShutdown(); p.tryFailure(t)} )
            return

        }

        r.getStatus() match {
          case Status.OK => // NOOP -> Just wait continue the loop processing data

          case Status.BUFFER_OVERFLOW =>  // resize and go again
            sys.error("Shouldn't have gotten here")

          case Status.BUFFER_UNDERFLOW => // Need more data
            readLeftover = b

            if ((r.getHandshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
              r.getHandshakeStatus == HandshakeStatus.FINISHED) && !out.isEmpty) {          // We got some data so send it
              p.success(joinBuffers(out))
            }
            else {
              val readsize = if (size > 0) size - bytesRead else size
              channelRead(math.min(readsize, maxNetSize)).onComplete {
                case Success(b) => readLoop(b, readsize, out, p)
                case Failure(f) => p.tryFailure(f)
              }(trampoline)
            }
            return

          // It is up to the next stage to call shutdown, if that is what they want
          case Status.CLOSED =>
            if (!out.isEmpty) p.success(joinBuffers(out))
            else p.failure(EOF)
            return
        }
      } catch {
        case t: SSLException =>
          logger.warn(t)("SSL Error")
          stageShutdown()
          p.failure(t)
      }
    }

    sys.error("Shouldn't get here")
  }

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    val p = Promise[Unit]
    writeLoop(0, data.toArray, new ListBuffer, p)
    p.future
  }

  def writeRequest(data: ByteBuffer): Future[Unit] = {
    val arr = new Array[ByteBuffer](1)
    arr(0) = data
    val p = Promise[Unit]
    writeLoop(0, arr, new ListBuffer, p)
    p.future
  }

  private def writeLoop(written: Int, buffers: Array[ByteBuffer], out: ListBuffer[ByteBuffer], p: Promise[Unit]) {

    val o = ScratchBuffer.getScratchBuffer(maxBuffer)
    var wr = written

    try while (true) {
      o.clear()
      val r = engine.wrap(buffers, o)

      logger.debug(s"Write request result: $r, $o")

      r.getHandshakeStatus match {
        case HandshakeStatus.NOT_HANDSHAKING | HandshakeStatus.NOT_HANDSHAKING => // NOOP
        case _ => // need to handshake
          if (o.position() > 0) { // need to send out some data first, then continue the handshake
            o.flip()
            channelWrite(copyBuffer(o)).onComplete {
              case Success(_) =>
                sslHandshake(emptyBuffer, r, (_, _) => writeLoop(wr, buffers, out, p),  t => {stageShutdown(); p.failure(t)} )

              case Failure(t) => p.failure(t)
            }(trampoline)
          } else {
            sslHandshake(emptyBuffer, r, (_, _) => writeLoop(wr, buffers, out, p),  t => {stageShutdown(); p.failure(t)} )
          }
          return
      }

      r.getStatus match {
        case Status.OK =>   // Successful encode
          if (o.position() > 0) {
            o.flip()
            wr += o.remaining()
            out += copyBuffer(o)
            o.clear()

            // See if we should write
            if (maxSubmission > 0 && wr > maxSubmission) {
              // Need to write
              channelWrite(out).onComplete{
                case Success(_)    => writeLoop(0, buffers, new ListBuffer, p)
                case f@ Failure(_) => p.tryComplete(f)
              }(directec)
              return
            }
          }

        case Status.CLOSED =>
          if (!out.isEmpty) {
            p.completeWith(channelWrite(out))
            return
          }
          else {
            p.tryFailure(EOF)
            return
          }

        case Status.BUFFER_OVERFLOW => // Should always have a large enough buffer
          sys.error("Shouldn't get here")

        case Status.BUFFER_UNDERFLOW => // Need more data. Should probably never get here
          if (o.position() > 0) {
            o.flip()
            out += copyBuffer(o)
          }
          p.completeWith(channelWrite(out))
          return
      }

      if (areEmpty(buffers)) {
        if (o != null && o.position() > 0) {
          o.flip()
          out += copyBuffer(o)
        }

        p.completeWith(channelWrite(out))
        return
      }
    } catch {
      case t: SSLException =>
        logger.warn(t)("SSL Error")
        stageShutdown()
        p.failure(t)
    }

    sys.error("Shouldn't get here.")
  }

  override protected def stageShutdown(): Unit = {
    ScratchBuffer.clearBuffer()
    super.stageShutdown()
  }

  private def allocate(size: Int): ByteBuffer = ByteBuffer.allocate(size)

  private def copyBuffer(b: ByteBuffer): ByteBuffer = {
    val bb = allocate(b.remaining())
    bb.put(b).flip()
    bb
  }

  private def joinBuffers(buffers: Seq[ByteBuffer]): ByteBuffer = {
    val sz = buffers.foldLeft(0)((sz, o) => sz + o.remaining())
    val b = allocate(sz)
    buffers.foreach(b.put)

    b.flip()
    b
  }

  private def areEmpty(buffers: Array[ByteBuffer]): Boolean = {
    @tailrec
    def areEmpty(buffers: Array[ByteBuffer], i: Int): Boolean = {
      if (buffers(i).hasRemaining) false
      else if (i > 0) areEmpty(buffers, i - 1)
      else true
    }
    if (buffers.length > 0) areEmpty(buffers, buffers.length - 1)
    else true
  }

  private def runTasks() {
    var t = engine.getDelegatedTask
    while(t != null) {
      t.run()
      t = engine.getDelegatedTask
    }
  }
}


