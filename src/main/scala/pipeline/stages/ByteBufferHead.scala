package pipeline
package stages

import java.nio.channels.{AsynchronousSocketChannel => NioChannel, ShutdownChannelGroupException, CompletionHandler}
import java.nio.ByteBuffer
import scala.concurrent.{Promise, Future}
import pipeline.Command._
import java.io.IOException


/**
* @author Bryce Anderson
*         Created on 1/4/14
*/
class ByteBufferHead(channel: NioChannel,
                     val name: String = "ByteBufferHeadStage",
                     bufferSize: Int = 20*1024) extends HeadStage[ByteBuffer] {

  private val bytes = ByteBuffer.allocate(bufferSize)

  def writeRequest(data: ByteBuffer): Future[Unit] = {
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

  

  def readRequest(): Future[ByteBuffer] = {
      
    val p = Promise[ByteBuffer]
    bytes.clear()

    channel.read(bytes, null: Null, new CompletionHandler[Integer, Null] {
      def failed(exc: Throwable, attachment: Null): Unit = {
        exc match {
          case e: ShutdownChannelGroupException => shutdown()
        }
        p.failure(exc)
      }

      def completed(result: Integer, attachment: Null) {
        bytes.flip()
        p.success(bytes)
      }
    })
    
    p.future
  }

  override def shutdown(): Unit = closeRequest()

  private def closeRequest() {
    try channel.close()
    catch {  case e: IOException => /* Don't care */ }
  }

  override def outboundCommand(cmd: Command): Unit = cmd match {
    case Shutdown         => closeRequest()
    case cmd              => super.outboundCommand(cmd)
  }
}
