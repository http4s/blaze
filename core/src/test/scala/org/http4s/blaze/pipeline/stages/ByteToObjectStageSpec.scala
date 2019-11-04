package org.http4s.blaze.pipeline.stages

import org.specs2.mutable._

import java.nio.ByteBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.ImmutableArray

class ByteToObjectStageSpec extends Specification {
  sealed trait Msg { def tag: Byte }
  case class One(byte: Byte) extends Msg { def tag = 0 }
  case class Two(short: Short) extends Msg { def tag = 1 }

  class MsgCodec extends ByteToObjectStage[Msg] {
    val maxBufferSize: Int = -1
    def name: String = "TestCodec"

    def messageToBuffer(in: Msg): collection.Seq[ByteBuffer] = {
      val b = ByteBuffer.allocate(3)
      b.put(in.tag)
      in match {
        case One(byte) => b.put(byte)
        case Two(short) => b.putShort(short)
      }
      b.flip
      b :: Nil
    }

    def bufferToMessage(in: ByteBuffer): Option[Msg] = {
      if (in.remaining() < 2) return None

      in.get(0) match {
        case 0 =>
          val o = One(in.get(1))
          in.position(2)
          Some(o)

        case 1 =>
          if (in.remaining() < 3) return None
          val o = Two(in.getShort(1))
          in.position(3)
          Some(o)

        case i => sys.error(s"Invalid frame number: $i")
      }
    }
  }

  def oneBuffer = {
    val b = ByteBuffer.allocate(2)
    b.put(0.toByte).put(1.toByte).flip()
    b
  }

  def twoBuffer = {
    val b = ByteBuffer.allocate(3)
    b.put(1.toByte).putShort(2.toShort).flip()
    b
  }

  def buildPipeline(buffs: Seq[ByteBuffer]): MsgCodec = {
    val head = new SeqHead(buffs)
    val c = new MsgCodec
    val b = new LeafBuilder(c)
    b.base(head)
    c
  }

  "ByteToObjectStage" should {
    "Encode One frame" in {
      val msg = new MsgCodec().messageToBuffer(One(1)).head
      msg.get should_== 0
      msg.get should_== 1
    }

    "Encode Two frame" in {
      val msg = new MsgCodec().messageToBuffer(Two(2)).head
      msg.get should_== 1
      msg.getShort should_== 2
    }

    "Decode One frame" in {
      val one = new MsgCodec().bufferToMessage(oneBuffer)
      one should_== Some(One(1))
    }

    "Decode Two frame" in {
      val two = new MsgCodec().bufferToMessage(twoBuffer)
      two should_== Some(Two(2))
    }

    "Hault on partial Two frame" in {
      val buff = twoBuffer
      buff.limit(2)
      val codec = new MsgCodec()
      val two = codec.bufferToMessage(buff)
      two should_== None

      buff.limit(3)
      codec.bufferToMessage(buff) should_== Some(Two(2))
    }

    "Decode a series of buffers" in {
      val c = buildPipeline(oneBuffer :: twoBuffer :: Nil)
      Await.result(c.readRequest(-1), 2.seconds) should_== One(1)
      Await.result(c.readRequest(-1), 2.seconds) should_== Two(2)
    }

    "Decode one large buffer" in {
      val b = ByteBuffer.allocate(oneBuffer.remaining() + twoBuffer.remaining())
      b.put(oneBuffer).put(twoBuffer)
      b.flip()

      val c = buildPipeline(b :: Nil)
      Await.result(c.readRequest(-1), 2.seconds) should_== One(1)
      Await.result(c.readRequest(-1), 2.seconds) should_== Two(2)
    }

    "Decode a series of one byte buffers" in {
      val b = ByteBuffer.allocate(oneBuffer.remaining() + twoBuffer.remaining())
      b.put(oneBuffer).put(twoBuffer)

      val buffs = b.array().map { byte =>
        val b = ByteBuffer.allocate(1)
        b.put(byte).flip()
        b
      }

      val c = buildPipeline(ImmutableArray(buffs))
      Await.result(c.readRequest(-1), 2.seconds) should_== One(1)
      Await.result(c.readRequest(-1), 2.seconds) should_== Two(2)
    }
  }
}
