/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.ImmutableArray

class ByteToObjectStageSuite extends BlazeTestSuite {
  private sealed trait Msg { def tag: Byte }
  private case class One(byte: Byte) extends Msg { def tag = 0 }
  private case class Two(short: Short) extends Msg { def tag = 1 }

  private class MsgCodec extends ByteToObjectStage[Msg] {
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

  private def oneBuffer = {
    val b = ByteBuffer.allocate(2)
    b.put(0.toByte).put(1.toByte).flip()
    b
  }

  private def twoBuffer = {
    val b = ByteBuffer.allocate(3)
    b.put(1.toByte).putShort(2.toShort).flip()
    b
  }

  private def buildPipeline(buffs: Seq[ByteBuffer]): MsgCodec = {
    val head = new SeqHead(buffs)
    val c = new MsgCodec
    val b = new LeafBuilder(c)
    b.base(head)
    c
  }

  test("A ByteToObjectStage should encode One frame") {
    val msg = new MsgCodec().messageToBuffer(One(1)).head
    assertEquals(msg.get, 0.toByte)
    assertEquals(msg.get, 1.toByte)
  }

  test("A ByteToObjectStage should encode Two frame") {
    val msg = new MsgCodec().messageToBuffer(Two(2)).head
    assertEquals(msg.get, 1.toByte)
    assertEquals(msg.getShort, 2.toShort)
  }

  test("A ByteToObjectStage should decode One frame") {
    val one = new MsgCodec().bufferToMessage(oneBuffer)
    assertEquals(one, Some(One(1)))
  }

  test("A ByteToObjectStage should decode Two frame") {
    val two = new MsgCodec().bufferToMessage(twoBuffer)
    assertEquals(two, Some(Two(2)))
  }

  test("A ByteToObjectStage should hault on partial Two frame") {
    val buff = twoBuffer
    buff.limit(2)
    val codec = new MsgCodec()
    val two = codec.bufferToMessage(buff)
    assert(two.isEmpty)

    buff.limit(3)
    assertEquals(codec.bufferToMessage(buff), Some(Two(2)))
  }

  test("A ByteToObjectStage should decode a series of buffers") {
    val c = buildPipeline(oneBuffer :: twoBuffer :: Nil)

    val result =
      for {
        r1 <- c.readRequest(-1)
        r2 <- c.readRequest(-1)
      } yield r1 -> r2

    assertFuture(result, One(1) -> Two(2))
  }

  test("A ByteToObjectStage should decode one large buffer") {
    val b = ByteBuffer.allocate(oneBuffer.remaining() + twoBuffer.remaining())
    b.put(oneBuffer).put(twoBuffer)
    b.flip()

    val c = buildPipeline(b :: Nil)

    val result =
      for {
        r1 <- c.readRequest(-1)
        r2 <- c.readRequest(-1)
      } yield r1 -> r2

    assertFuture(result, One(1) -> Two(2))
  }

  test("A ByteToObjectStage should decode a series of one byte buffers") {
    val b = ByteBuffer.allocate(oneBuffer.remaining() + twoBuffer.remaining())
    b.put(oneBuffer).put(twoBuffer)

    val buffs = b.array().map { byte =>
      val b = ByteBuffer.allocate(1)
      b.put(byte).flip()
      b
    }

    val c = buildPipeline(ImmutableArray(buffs))

    val result =
      for {
        r1 <- c.readRequest(-1)
        r2 <- c.readRequest(-1)
      } yield r1 -> r2

    assertFuture(result, One(1) -> Two(2))
  }
}
