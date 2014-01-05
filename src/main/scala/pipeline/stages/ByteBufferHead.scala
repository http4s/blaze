package pipeline
package stages

import java.nio.channels.{AsynchronousSocketChannel => NioChannel, ShutdownChannelGroupException, CompletionHandler}
import java.nio.ByteBuffer
import scala.concurrent.{Promise, Future}
import pipeline.Command._


/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class ByteBufferHead(channel: NioChannel,
                     val name: String = "ByteBufferHeadStage",
                     bufferSize: Int = 40*1024) extends HeadStage[ByteBuffer] {

  private val bytes = ByteBuffer.allocate(bufferSize)

  def handleOutbound(data: ByteBuffer): Future[Unit] = {
    val f = Promise[Unit]
    channel.write(data, null: Null, new CompletionHandler[Integer, Null] {
      def failed(exc: Throwable, attachment: Null) {
        f.tryFailure(exc)
      }

      def completed(result: Integer, attachment: Null) {
        f.trySuccess()
      }
    })

    f.future
  }

  private def doClose() {
    ???
  }

  private def doRead(size: Int) {
    try {
      val buffer = if (size <= bufferSize) {
        bytes.clear()
        bytes
      }
      else {
        ByteBuffer.allocateDirect(size)
      }

      channel.read(buffer, null: Null, new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null): Unit = exc match {
          case e: ShutdownChannelGroupException => shutdown()
        }

        def completed(result: Integer, attachment: Null) {
          buffer.flip()
          sendInbound(buffer)
        }
      })
    }
  }

  override def shutdown(): Unit = {

  }

  override def outboundCommand(cmd: Command): Unit = cmd match {
    case req: ReadRequest => doRead(if (req.bytes > 0) req.bytes else bufferSize)
    case Shutdown         => doClose()
    case cmd              => super.outboundCommand(cmd)
  }
}
