package blaze.pipeline
package stages

import java.nio.channels.{AsynchronousSocketChannel => NioChannel, ShutdownChannelGroupException, CompletionHandler}
import java.nio.ByteBuffer
import scala.concurrent.{Promise, Future}
import Command._
import java.io.IOException
import java.util.Date


/**
* @author Bryce Anderson
*         Created on 1/4/14
*/
class ByteBufferHead(channel: NioChannel,
                     val name: String = "ByteBufferHeadStage",
                     bufferSize: Int = 20*1024) extends HeadStage[ByteBuffer] {

  private val bytes = ByteBuffer.allocate(bufferSize)

  def writeRequest(data: ByteBuffer): Future[Unit] = {

    if (!data.hasRemaining() && data.position > 0) {
      logger.warn("Received write request with non-zero position but ZERO available" +
                 s"bytes at ${new Date} on blaze.channel $channel: $data, head: $next")
      return Future.successful()
    }

    val f = Promise[Unit]

    def go(i: Int) {
      channel.write(data, null: Null, new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null) {
          f.tryFailure(exc)
        }

        def completed(result: Integer, attachment: Null) {
          if (result.intValue < i) go(i - result.intValue)  // try to write again
          else f.trySuccess()      // All done
        }
      })
    }
    go(data.remaining())

    f.future
  }

  def readRequest(size: Int): Future[ByteBuffer] = {
      
    val p = Promise[ByteBuffer]
    bytes.clear()

    if (size >= 0 && size < bufferSize)
      bytes.limit(size)

    channel.read(bytes, null: Null, new CompletionHandler[Integer, Null] {
      def failed(exc: Throwable, attachment: Null): Unit = {
        exc match {
          case e: IOException                   => channelUnexpectedlyClosed(e)
          case e: ShutdownChannelGroupException => channelUnexpectedlyClosed(e)
          case _: Throwable                     => // Don't know what to do
        }
        p.failure(exc)
      }

      def completed(i: Integer, attachment: Null) {
        if (i.intValue() >= 0) {
          bytes.flip()
          p.trySuccess(bytes)
        } else {   // must be end of stream
          p.tryFailure(EOF)
          closeChannel()
          sendInboundCommand(Shutdown)
        }
      }
    })
    
    p.future
  }

  override def shutdown(): Unit = closeChannel()

  private def channelUnexpectedlyClosed(e: Throwable) {
    closeChannel()
    sendInboundCommand(Error(e))
    sendInboundCommand(Shutdown)
  }

  private def closeChannel() {
    logger.trace("channelClose")
    try channel.close()
    catch {  case e: IOException => /* Don't care */ }

  }

  override def outboundCommand(cmd: Command): Unit = cmd match {
    case Shutdown         => closeChannel()
    case Error(e)         => channelUnexpectedlyClosed(e)
    case cmd              => // NOOP
  }
}
