//package org.http4s.blaze.http.http2
//
//import java.nio.ByteBuffer
//
//import scala.collection.mutable
//
//sealed abstract class ProtocolFrame private extends Product with Serializable {
//  def write(acc: mutable.Buffer[ByteBuffer]): Int
//}
//
//object ProtocolFrame {
//  case class Goaway(lastHandleStream: Int, cause: Http2Exception) extends ProtocolFrame {
//    override def write(acc: mutable.Buffer[ByteBuffer]): Int = {
//      val buffer = FrameSerializer.mkGoAwayFrame(lastHandleStream, cause)
//      val bytes = buffer.remaining
//      acc += buffer
//      bytes
//    }
//  }
//
//  case class Rst(streamId: Int, errorCode: Long) extends ProtocolFrame {
//    override def write(acc: mutable.Buffer[ByteBuffer]): Int = {
//      val buffer = FrameSerializer.mkRstStreamFrame(streamId, errorCode)
//      val bytes = buffer.remaining
//      acc += buffer
//      bytes
//    }
//  }
//
//  case class Raw(data: Seq[ByteBuffer]) extends ProtocolFrame {
//    override def write(acc: mutable.Buffer[ByteBuffer]): Int = writeRaw(acc, data)
//  }
//
//  case class HeadersBlock(data: Seq[ByteBuffer]) extends ProtocolFrame {
//    override def write(acc: mutable.Buffer[ByteBuffer]): Int = writeRaw(acc, data)
//  }
//
//  case object Empty extends ProtocolFrame {
//    override def write(acc: mutable.Buffer[ByteBuffer]): Int = 0
//  }
//
//  private[this] def writeRaw(acc: mutable.Buffer[ByteBuffer], data: Seq[ByteBuffer]): Int = {
//    var bytes = 0
//    data.foreach { b =>
//      bytes += b.remaining
//      acc += b
//    }
//    bytes
//  }
//}