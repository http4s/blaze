package blaze.channel.nio2

import blaze.pipeline.{Command, TailStage, HeadStage, LeafBuilder}
import java.nio.ByteBuffer
import java.nio.channels.{CompletionHandler, AsynchronousSocketChannel, AsynchronousChannelGroup}
import java.net.SocketAddress
import scala.concurrent.{Future, Promise}

/**
 * @author Bryce Anderson
 *         Created on 2/4/14
 */

/** A factory for opening TCP connetions to remote sockets
  *
  * Provides a way to easily make TCP connections which can then serve as the Head for a pipeline
  *
  * @param bufferSize default buffer size to perform reads
  * @param group the [[java.nio.channels.AsynchronousChannelGroup]] which will manage the connection
  */
class ClientChannelFactory(bufferSize: Int = 8*1024, group: AsynchronousChannelGroup = null) {

  def connect(remoteAddress: SocketAddress, bufferSize: Int = bufferSize): Future[HeadStage[ByteBuffer]] = {
    val p = Promise[HeadStage[ByteBuffer]]

    val ch = AsynchronousSocketChannel.open(group)
    ch.connect(remoteAddress, null: Null, new CompletionHandler[Void, Null] {
      def failed(exc: Throwable, attachment: Null) {
        p.failure(exc)
      }

      def completed(result: Void, attachment: Null) {
        p.success(new ByteBufferHead(ch, bufferSize = bufferSize))
      }
    })

    p.future
  }

}

object ClientChannelFactory {

  import java.nio.charset.StandardCharsets.US_ASCII

  def msg = {
      ByteBuffer.wrap("""GET / HTTP/1.1
        |Host: www.google.com
        |
      """.stripMargin.replace("\n", "\r\n").getBytes(US_ASCII))
  }

  def test() {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import scala.util.{Failure, Success}
    import java.net.InetSocketAddress

    val addr = new InetSocketAddress("www.google.com", 80)
    val f = new ClientChannelFactory()
    val stg = f.connect(addr)

    val h = Await.result(stg, 4.seconds)
    val t = new TailStage[ByteBuffer] { def name = ""}
    LeafBuilder(t).base(h)

    t.channelWrite(msg).onSuccess{
      case _ => t.channelRead().onComplete{
        case Success(b) =>
          println(US_ASCII.decode(b))
          t.inboundCommand(Command.Disconnect)

        case Failure(_) =>
          println("Failed.")
          t.inboundCommand(Command.Disconnect)
      }
    }

  }
}
