package org.http4s.blaze.util

import org.specs2.mutable._
import java.nio.ByteBuffer

class BufferToolsSpec extends Specification {

  def b(i: Int = 1) = {
    val b = ByteBuffer.allocate(4)
    b.putInt(i).flip()
    b
  }

  "BufferTools.concatBuffers" should {
    "discard null old buffers" in {
      val bb = b()
      BufferTools.concatBuffers(null, bb) should_== bb
    }

    "discard empty buffers" in {
      val b1 = b();
      val b2 = b()
      b1.getInt()
      BufferTools.concatBuffers(b1, b2) should_== b2
    }

    "concat two buffers" in {
      val b1 = b(1);
      val b2 = b(2)
      val a = BufferTools.concatBuffers(b1, b2)
      a.remaining() should_== 8
      a.getInt() should_== 1
      a.getInt() should_== 2
    }

    "append the result of one to the end of another if there is room" in {
      val b1 = ByteBuffer.allocate(9)
      b1.position(1) // offset by 1 to simulated already having read a byte
      b1.putInt(1).flip().position(1)
      val b2 = b(2)

      val bb = BufferTools.concatBuffers(b1, b2)
      bb should_== b1
      bb.position() should_== 1
      bb.getInt() should_== 1
      bb.getInt() should_== 2
    }

    "a slice of buffer a is not corrupted by concat" in {
      val a = ByteBuffer.allocate(8)
      val b = ByteBuffer.allocate(4)

      a.putInt(123).putInt(456).flip()
      b.putInt(789).flip()
      val slice = a.slice() // should contain the same view as buffer `a` right now

      a.getInt() must_== 123

      val c = BufferTools.concatBuffers(a, b)

      slice.getInt() must_== 123
      slice.getInt() must_== 456

      c.getInt() must_== 456
      c.getInt() must_== 789
    }
  }

  "BufferTools.checkEmpty" should {

    "check if buffers are empty" in {
      BufferTools.checkEmpty(Array(ByteBuffer.allocate(0), ByteBuffer.allocate(3))) must_== false
      BufferTools.checkEmpty(Seq(ByteBuffer.allocate(0), ByteBuffer.allocate(3))) must_== false

      BufferTools.checkEmpty(Array(ByteBuffer.allocate(0), ByteBuffer.allocate(0))) must_== true
      BufferTools.checkEmpty(Seq(ByteBuffer.allocate(0), ByteBuffer.allocate(0))) must_== true

      BufferTools.checkEmpty(Array(ByteBuffer.allocate(3))) must_== false
      BufferTools.checkEmpty(Seq(ByteBuffer.allocate(3))) must_== false

      BufferTools.checkEmpty(Array(ByteBuffer.allocate(0))) must_== true
      BufferTools.checkEmpty(Seq(ByteBuffer.allocate(0))) must_== true

      BufferTools.checkEmpty(Array[ByteBuffer]()) must_== true
      BufferTools.checkEmpty(Seq()) must_== true
    }
  }

  "BufferTools.dropEmpty" should {

    val buff0 = ByteBuffer.allocate(0)
    val buff1 = ByteBuffer.allocate(1)

    "Find index of first Non-empty buffer" in {
      val arr1 = Array(buff0)
      BufferTools.dropEmpty(arr1) must_== 0

      val arr2 = Array(buff0, buff1)
      BufferTools.dropEmpty(arr2) must_== 1

      val arr3 = Array(buff1, buff0)
      BufferTools.dropEmpty(arr3) must_== 0
    }

    "drop empty buffers until the first non-empty buffer except the last" in {
      val arr = Array(buff0, buff0)
      BufferTools.dropEmpty(arr) must_== 1
      (arr(0) eq BufferTools.emptyBuffer) must_== true
      (arr(1) eq buff0) must_== true

      val arr2 = Array(buff1, buff1)
      BufferTools.dropEmpty(arr2) must_== 0
      (arr2(0) eq buff1) must_== true
      (arr2(1) eq buff1) must_== true

      val arr3 = Array(buff0, buff1)
      BufferTools.dropEmpty(arr3) must_== 1
      (arr3(0) eq BufferTools.emptyBuffer) must_== true
      (arr3(1) eq buff1) must_== true
    }
  }

  "BufferTools.copyBuffers" should {

    "copy a single buffer into a larger buffer" in {
      val buffs = getBuffers(1)
      val target = ByteBuffer.allocate(10)

      BufferTools.copyBuffers(buffs, target) must_== 4
      target.position() must_== 4
      buffs(0).position() must_== 0
    }

    "copy a single buffer into a smaller buffer" in {
      val buffs = getBuffers(1)
      val target = ByteBuffer.allocate(2)

      BufferTools.copyBuffers(buffs, target) must_== 2
      target.position() must_== 2
      buffs(0).position() must_== 0
    }

    "copy multiple buffers int a larger buffer" in {
      val buffs = getBuffers(2)
      val target = ByteBuffer.allocate(10)

      BufferTools.copyBuffers(buffs, target) must_== 8
      target.position() must_== 8

      forall(buffs) { buff =>
        buff.position() must_== 0
      }
    }

    "copy multiple buffers int a smaller buffer" in {
      val buffs = getBuffers(2)

      {
        val target = ByteBuffer.allocate(2)

        BufferTools.copyBuffers(buffs, target) must_== 2
        target.position() must_== 2

        forall(buffs) { buff =>
          buff.position() must_== 0
        }
      }

      {
        val target = ByteBuffer.allocate(6)

        BufferTools.copyBuffers(buffs, target) must_== 6
        target.position() must_== 6

        forall(buffs) { buff =>
          buff.position() must_== 0
        }
      }
    }

    "handle null buffers in the array" in {
      val buffs = getBuffers(2)
      buffs(0) = null

      val target = ByteBuffer.allocate(10)

      BufferTools.copyBuffers(buffs, target) must_== 4
      target.position() must_== 4

      buffs(1).position() must_== 0
    }
  }

  "BufferTools.fastForwardBuffers" should {
    "fast forward an entire array" in {
      val buffers = getBuffers(4)

      BufferTools.fastForwardBuffers(buffers, 4*4) must_== true
      forall(buffers) { buffer =>
        buffer.remaining must_== 0
      }
    }

    "fast forward only part of an array" in {
      val buffers = getBuffers(2)

      BufferTools.fastForwardBuffers(buffers, 5) must_== true

      buffers(0).remaining must_== 0
      buffers(1).remaining must_== 3
    }

    "fast forward past an array" in {
      val buffers = getBuffers(2)
      BufferTools.fastForwardBuffers(buffers, 10) must_== false

      forall(buffers){ buffer =>
        buffer.remaining must_== 0
      }
    }

    "fast forward an array with null elements" in {
      val buffers = getBuffers(2)
      buffers(0) = null

      BufferTools.fastForwardBuffers(buffers, 2) must_== true

      buffers(1).remaining must_== 2
    }
  }

  "BufferTools.areDirectOrEmpty" should {
    "Be true for a collection of direct buffers" in {
      BufferTools.areDirectOrEmpty(getDirect(4)) must_== true
    }

    "Be true for a collection of nulls" in {
      BufferTools.areDirectOrEmpty(Array[ByteBuffer](null, null)) must_== true
    }

    "Be true for a collection of direct buffers with a null element" in {
      val buffs = getDirect(4)
      buffs(3) = null
      BufferTools.areDirectOrEmpty(buffs) must_== true
    }

    "Be false for a collection with a non-empty heap buffer in it" in {
      val buffs = getDirect(4)
      buffs(3) = ByteBuffer.allocate(4)
      BufferTools.areDirectOrEmpty(buffs) must_== false
    }

    "Be true for a collection with an empty heap buffer in it" in {
      val buffs = getDirect(4)
      buffs(3) = ByteBuffer.allocate(0)
      BufferTools.areDirectOrEmpty(buffs) must_== true
    }
  }

  private def getBuffers(count: Int): Array[ByteBuffer] = getBuffersBase(count, false)

  private def getDirect(count: Int): Array[ByteBuffer] = getBuffersBase(count, true)

  private def getBuffersBase(count: Int, direct: Boolean): Array[ByteBuffer] = {
    (0 until count).map { i =>
      val buffer = if (direct) ByteBuffer.allocateDirect(4) else ByteBuffer.allocate(4)
      buffer.putInt(4).flip()
      buffer
    }.toArray
  }
}
