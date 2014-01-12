package blaze.pipeline.stages

import scala.annotation.switch

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

  logger.trace(s"--------------------- SSL Engine has maxNetSize: $maxNetSize and maxAppSize: $maxAppSize")

  private var readLeftover: ByteBuffer = null

  private def allocate(size: Int): ByteBuffer = ByteBuffer.allocate(size)



  def readRequest(size: Int): Future[ByteBuffer] = {
    channelRead(size).flatMap(b => readRequestLoop(b, size, new ListBuffer[ByteBuffer]))(directec)
  }

  // If we have at least one output buffer, we won't read more data until another request is made
  private def readRequestLoop(buffer: ByteBuffer, size: Int, out: ListBuffer[ByteBuffer]): Future[ByteBuffer] = {

    // Consolidate buffers
    val b = {
      if (readLeftover != null && readLeftover.remaining() > 0) {
        if (readLeftover.capacity() < readLeftover.remaining() + buffer.remaining()) {
          val n = allocate(readLeftover.remaining() + buffer.remaining())
          n.put(readLeftover)
          readLeftover = n
        }

        readLeftover.put(buffer)
        readLeftover
      } else buffer
    }

    readLeftover = null

    var bytesRead = 0

    while(true) {
      val o = allocate(maxNetSize)
      val r = engine.unwrap(b, o)

      if (r.bytesProduced() > 0) {
        bytesRead += r.bytesProduced()
        o.flip()
        out += o
      }

      logger.trace(s"SSL Status: $r  /////////////")

      r.getHandshakeStatus match {
        case HandshakeStatus.FINISHED =>
        case HandshakeStatus.NEED_UNWRAP =>  // must need more data
          if (r.getStatus == Status.BUFFER_UNDERFLOW) {

            saveRead(b)

            return channelRead().flatMap{ rb =>
              logger.trace(s"################### UNWRAP UNDERFLOW buffer $b, $rb")
              readRequestLoop(rb, size - bytesRead, out)
            }(trampoline)
          }

        case HandshakeStatus.NOT_HANDSHAKING =>  // Noop
        case HandshakeStatus.NEED_TASK =>
          runTasks()

        case HandshakeStatus.NEED_WRAP =>

          val empty = allocate(0)   // Dummy
          empty.flip()

          val o = allocate(maxNetSize)
          var r = engine.wrap(empty, o)

          logger.trace(s"Handshake NEED_WRAP result: $r, $o, $b")
          assert(r.bytesProduced() > 0)

          o.flip()
          return channelWrite(o).flatMap(_ => readRequestLoop(b, size, out))(trampoline)

      }

      r.getStatus() match {
        case Status.OK => // NOOP
        case Status.BUFFER_OVERFLOW =>  // just go again
        case Status.BUFFER_UNDERFLOW => // Need more data

          saveRead(b)

          if ((r.getHandshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
              r.getHandshakeStatus == HandshakeStatus.FINISHED) && !out.isEmpty) {          // We got some data so send it
            return Future.successful(joinBuffers(out))
          }

          else return channelRead(math.max(size, maxNetSize))
                      .flatMap(readRequestLoop(_, size, out))(trampoline)

        case Status.CLOSED =>
          if (!out.isEmpty) return Future.successful(joinBuffers(out))
          else return Future.failed(EOF)
      }
    }

    sys.error("Shouldn't get here")
  }

  private def saveRead(b: ByteBuffer) {
    if (b.hasRemaining) {
      readLeftover = ByteBuffer.allocate(b.remaining())
      readLeftover.put(b)
      readLeftover.flip()
    }
  }


  override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = writeLoop(data.toArray, new ListBuffer)

  def writeRequest(data: ByteBuffer): Future[Any] = writeLoop(Array(data), new ListBuffer)

  private def writeLoop(buffers: Array[ByteBuffer], out: ListBuffer[ByteBuffer]): Future[Any] = {

    var o: ByteBuffer = null

    while (true) {

      if (o == null) o = allocate(maxNetSize)

      val r = engine.wrap(buffers, o)

      logger.trace(s"Write request result: $r")

      if (r.bytesProduced() > 0) {
        o.flip()
        out += o
        o = null
      }

      r.getHandshakeStatus match {
        case HandshakeStatus.FINISHED =>
        case HandshakeStatus.NEED_WRAP =>
        case HandshakeStatus.NOT_HANDSHAKING =>

        case HandshakeStatus.NEED_TASK => runTasks()

        case HandshakeStatus.NEED_UNWRAP =>
          return channelRead().flatMap { b =>
            val empty = allocate(0)
            engine.unwrap(b, empty)

            if (b.remaining() > 0) {
              readLeftover = joinBuffers(readLeftover::b::Nil)
            }

            writeLoop(buffers, out)
          }(trampoline)

      }

      r.getStatus match {
        case Status.OK =>

        case Status.CLOSED =>
          if (!out.isEmpty) return channelWrite(out)
          else return Future.failed(EOF)

        case Status.BUFFER_OVERFLOW =>

        case Status.BUFFER_UNDERFLOW =>
          return channelWrite(out)
      }

      if (r.bytesProduced() == 0 && r.bytesConsumed() == 0)
        return channelWrite(out)
    }

    sys.error("Shouldn't get here.")
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
