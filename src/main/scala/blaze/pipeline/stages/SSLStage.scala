package blaze.pipeline.stages

import scala.annotation.{tailrec, switch}

import javax.net.ssl.SSLEngine
import blaze.pipeline.MidStage
import java.nio.ByteBuffer
import scala.concurrent.Future
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus

import blaze.pipeline.Command.EOF

import scala.collection.mutable.ListBuffer

import blaze.util.Execution._

/**
 * @author Bryce Anderson
 *         Created on 1/11/14
 */
class SSLStage(engine: SSLEngine) extends MidStage[ByteBuffer, ByteBuffer] {

  def name: String = s"SSLStage"

  private val maxNetSize = engine.getSession.getPacketBufferSize
  private val maxAppSize = engine.getSession.getApplicationBufferSize

  private val ratio = maxNetSize.toDouble/maxAppSize.toDouble
  
  val empty = { val b = ByteBuffer.allocate(0); b.flip(); b }
  
  private var readLeftover: ByteBuffer = null

//  private val netBuffer = ByteBuffer.allocate(maxNetSize)
//  private val appBuffer = ByteBuffer.allocate(maxAppSize)

  private def allocate(size: Int): ByteBuffer = ByteBuffer.allocate(size)



  def readRequest(size: Int): Future[ByteBuffer] = {
    channelRead(size).flatMap(b => readRequestLoop(b, size, new ListBuffer[ByteBuffer]))(directec)
  }

  // If we have at least one output buffer, we won't read more data until another request is made
  private def readRequestLoop(buffer: ByteBuffer, size: Int, out: ListBuffer[ByteBuffer]): Future[ByteBuffer] = {

    // Consolidate buffers
    val b = {
      if (readLeftover != null && readLeftover.position() > 0) {
        if (readLeftover.remaining() < buffer.remaining()) {
          val n = allocate(readLeftover.remaining() + buffer.remaining())
          readLeftover.flip()
          n.put(readLeftover)
          readLeftover = n
        }

        readLeftover.put(buffer).flip()
        readLeftover
      } else buffer
    }

    readLeftover = null

    var bytesRead = 0
    var o: ByteBuffer = null

    while(true) {

      if (o == null) o = allocate(math.max(b.remaining() + 100, 2*1024))

      val r = engine.unwrap(b, o)

      if (r.bytesProduced() > 0) {
        bytesRead += r.bytesProduced()
        o.flip()
        out += o
        o = null
      }

      logger.trace(s"SSL Read Request Status: $r")

      r.getHandshakeStatus match {
        case HandshakeStatus.NEED_UNWRAP =>  // must need more data
          if (r.getStatus == Status.BUFFER_UNDERFLOW) {
            storeRead(b)
            return channelRead().flatMap(readRequestLoop(_, if (size > 0) size - bytesRead else size, out))(trampoline)
          }

        case HandshakeStatus.NEED_TASK => runTasks()
        case HandshakeStatus.NEED_WRAP =>

          val empty = allocate(0)   // Dummy
          empty.flip()

          val o = allocate(maxNetSize)

          assert(engine.wrap(empty, o).bytesProduced() > 0)

          o.flip()
          return channelWrite(o).flatMap(_ =>
            readRequestLoop(b, if (size > 0) size - bytesRead else size, out)
          )(trampoline)

        case _ => // NOOP
      }

      r.getStatus() match {
        case Status.OK => // NOOP

        case Status.BUFFER_OVERFLOW =>  // resize and do it again
          logger.trace(s"Buffer overflow: $o")

          val n = ByteBuffer.allocate(maxAppSize)
          o.flip()
          if (o.hasRemaining) n.put(o)
          o = n

        case Status.BUFFER_UNDERFLOW => // Need more data, and not in handshake (dealt with above)

          storeRead(b)

          if ((r.getHandshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
              r.getHandshakeStatus == HandshakeStatus.FINISHED) && !out.isEmpty) {          // We got some data so send it
            return Future.successful(joinBuffers(out))
          }

          else {
            val readsize = if (size > 0) size - bytesRead else size
            return channelRead(math.max(readsize, maxNetSize))
              .flatMap(readRequestLoop(_, readsize, out))(trampoline)
          }

        case Status.CLOSED =>
          if (!out.isEmpty) return Future.successful(joinBuffers(out))
          else return Future.failed(EOF)
      }
    }

    sys.error("Shouldn't get here")
  }

  private def storeRead(b: ByteBuffer) {
    if (b.hasRemaining) {
      if (readLeftover != null) {
        val n = ByteBuffer.allocate(readLeftover.remaining() + b.remaining())
        n.put(readLeftover)
        readLeftover = n
      } else readLeftover = ByteBuffer.allocate(b.remaining())

      readLeftover.put(b)
    }
  }


  override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = writeLoop(data.toArray, new ListBuffer)

  def writeRequest(data: ByteBuffer): Future[Any] = {
    val arr = new Array[ByteBuffer](1)
    arr(0) = data
    writeLoop(arr, new ListBuffer)
  }

  private def writeLoop(buffers: Array[ByteBuffer], out: ListBuffer[ByteBuffer]): Future[Any] = {

    var o: ByteBuffer = null

    while (true) {
      if (o == null) o = allocate(math.max(maxNetSize, (remaining(buffers)*ratio).toInt + 256))

      val r = engine.wrap(buffers, o)

      logger.trace(s"Write request result: $r, $o")

      r.getHandshakeStatus match {
        case HandshakeStatus.NEED_TASK => runTasks()

        case HandshakeStatus.NEED_UNWRAP =>
          return channelRead().flatMap { b =>
            engine.unwrap(b, empty)

            // TODO: this will almost certainly corrupt the state...
            //storeRead(b)
            assert(!b.hasRemaining)

            writeLoop(buffers, out)
          }(trampoline)

        case _ => // NOOP   FINISHED, NEED_WRAP, NOT_HANDSHAKING
      }

      r.getStatus match {
        case Status.OK =>   // NOOP

        case Status.CLOSED =>
          if (!out.isEmpty) return channelWrite(out)
          else return Future.failed(EOF)

        case Status.BUFFER_OVERFLOW => // Store it and leave room for a fresh buffer
          o.flip()
          out += o
          o = null

        case Status.BUFFER_UNDERFLOW =>
          o.flip()
          if (o.remaining() > 0) out += o
          return channelWrite(out)
      }

      if (!buffers(buffers.length - 1).hasRemaining) {
        o.flip()
        if (o.remaining() > 0) out += o
        return channelWrite(out)
      }
    }

    sys.error("Shouldn't get here.")
  }

  private def remaining(buffers: Array[ByteBuffer]): Long = {
    var acc = 0L
    var i = 0
    while (i < buffers.length) {
      acc += buffers(i).remaining()
      i += 1
    }
    acc
  }

  private def joinBuffers(buffers: Seq[ByteBuffer]): ByteBuffer = {
    val sz = buffers.foldLeft(0)((sz, o) => sz + o.remaining())
    val b = allocate(sz)
    buffers.foreach(b.put(_))

    b.flip()
    b
  }

  private def runTasks() {
    var t = engine.getDelegatedTask
    while(t != null) {
      t.run()
      t = engine.getDelegatedTask
    }
  }
}
