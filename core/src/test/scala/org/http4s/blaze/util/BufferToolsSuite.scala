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

package org.http4s.blaze.util

import java.nio.ByteBuffer

import munit.FunSuite

import scala.util.Try

class BufferToolsSuite extends FunSuite {
  private def b(i: Int = 1) = {
    val b = ByteBuffer.allocate(4)
    b.putInt(i).flip()
    b
  }

  test("BufferTools.concatBuffers should discard null old buffers") {
    val bb = b()

    assertEquals(BufferTools.concatBuffers(null, bb), bb)
  }

  test("BufferTools.concatBuffers should discard empty buffers") {
    val b1 = b();
    val b2 = b()
    b1.getInt()

    assertEquals(BufferTools.concatBuffers(b1, b2), b2)
  }

  test("BufferTools.concatBuffers should concat two buffers") {
    val b1 = b(1);
    val b2 = b(2)
    val a = BufferTools.concatBuffers(b1, b2)

    assertEquals(a.remaining(), 8)
    assertEquals(a.getInt(), 1)
    assertEquals(a.getInt(), 2)
  }

  test(
    "BufferTools.concatBuffers should append the result of one to the end of another if there is room") {
    val b1 = ByteBuffer.allocate(9)
    b1.position(1) // offset by 1 to simulated already having read a byte
    b1.putInt(1).flip().position(1)
    val b2 = b(2)

    val bb = BufferTools.concatBuffers(b1, b2)

    assertEquals(bb, b1)
    assertEquals(bb.position(), 1)
    assertEquals(bb.getInt(), 1)
    assertEquals(bb.getInt(), 2)
  }

  test("BufferTools.concatBuffers should a slice of buffer a is not corrupted by concat") {
    val a = ByteBuffer.allocate(8)
    val b = ByteBuffer.allocate(4)

    a.putInt(123).putInt(456).flip()
    b.putInt(789).flip()
    val slice = a.slice() // should contain the same view as buffer `a` right now

    assertEquals(a.getInt(), 123)

    val c = BufferTools.concatBuffers(a, b)

    assertEquals(slice.getInt(), 123)
    assertEquals(slice.getInt(), 456)
    assertEquals(c.getInt(), 456)
    assertEquals(c.getInt(), 789)
  }

  test("BufferTools.takeSlice should take a slice from a buffer") {
    val a = ByteBuffer.allocate(10)
    a.putInt(123).putInt(456).flip()

    assertEquals(a.remaining(), 8)

    val b = BufferTools.takeSlice(a, 4)

    assertEquals(a.remaining, 4) // 4 bytes were consumed
    assertEquals(a.getInt(), 456)

    assertEquals(b.remaining, 4)
    assertEquals(b.getInt(), 123)
  }

  test(
    "BufferTools.takeSlice should throw an `IllegalArgumentException` if you try to slice too many bytes") {
    val a = ByteBuffer.allocate(10)
    a.putInt(123).putInt(456).flip()
    assertEquals(a.remaining(), 8)

    val testedValue = Try(BufferTools.takeSlice(a, 10))

    testedValue.fold(
      {
        case _: IllegalArgumentException => ()
        case ex => fail(s"Unexpected exception type found $ex")
      },
      _ =>
        fail(
          "BufferTools.takeSlice should throw an `IllegalArgumentException` if you try to slice too many bytes")
    )
  }

  test(
    "BufferTools.takeSlice should throw an `IllegalArgumentException` if you try to slice negative bytes") {
    val a = ByteBuffer.allocate(10)
    a.putInt(123).putInt(456).flip()
    assertEquals(a.remaining(), 8)

    val testedValue = Try(BufferTools.takeSlice(a, -4))

    testedValue.fold(
      {
        case _: IllegalArgumentException => ()
        case ex => fail(s"Unexpected exception type found $ex")
      },
      _ =>
        fail(
          "BufferTools.takeSlice should throw an `IllegalArgumentException` if you try to slice negative bytes")
    )
  }

  test("BufferTools.checkEmpty should check if buffers are empty") {
    assertEquals(
      BufferTools.checkEmpty(Array(ByteBuffer.allocate(0), ByteBuffer.allocate(3))),
      false)
    assertEquals(BufferTools.checkEmpty(Seq(ByteBuffer.allocate(0), ByteBuffer.allocate(3))), false)

    assert(BufferTools.checkEmpty(Array(ByteBuffer.allocate(0), ByteBuffer.allocate(0))))
    assert(BufferTools.checkEmpty(Seq(ByteBuffer.allocate(0), ByteBuffer.allocate(0))))

    assertEquals(BufferTools.checkEmpty(Array(ByteBuffer.allocate(3))), false)
    assertEquals(BufferTools.checkEmpty(Seq(ByteBuffer.allocate(3))), false)

    assert(BufferTools.checkEmpty(Array(ByteBuffer.allocate(0))))
    assert(BufferTools.checkEmpty(Seq(ByteBuffer.allocate(0))))

    assert(BufferTools.checkEmpty(Array[ByteBuffer]()))
    assert(BufferTools.checkEmpty(Seq.empty))
  }

  private val buff0 = ByteBuffer.allocate(0)
  private val buff1 = ByteBuffer.allocate(1)

  test("BufferTools.dropEmpty should find index of first Non-empty buffer") {
    val arr1 = Array(buff0)
    assertEquals(BufferTools.dropEmpty(arr1), 0)

    val arr2 = Array(buff0, buff1)
    assertEquals(BufferTools.dropEmpty(arr2), 1)

    val arr3 = Array(buff1, buff0)
    assertEquals(BufferTools.dropEmpty(arr3), 0)
  }

  test(
    "BufferTools.dropEmpty should drop empty buffers until the first non-empty buffer except the last") {
    val arr = Array(buff0, buff0)
    assertEquals(BufferTools.dropEmpty(arr), 1)
    assert(arr(0) eq BufferTools.emptyBuffer)
    assert(arr(1) eq buff0)

    val arr2 = Array(buff1, buff1)
    assertEquals(BufferTools.dropEmpty(arr2), 0)
    assert(arr2(0) eq buff1)
    assert(arr2(1) eq buff1)

    val arr3 = Array(buff0, buff1)
    assertEquals(BufferTools.dropEmpty(arr3), 1)
    assert(arr3(0) eq BufferTools.emptyBuffer)
    assert(arr3(1) eq buff1)
  }

  test("BufferTools.copyBuffers should copy a single buffer into a larger buffer") {
    val buffs = getBuffers(1)
    val target = ByteBuffer.allocate(10)

    assertEquals(BufferTools.copyBuffers(buffs, target), 4)
    assertEquals(target.position(), 4)
    assertEquals(buffs(0).position(), 0)
  }

  test("BufferTools.copyBuffers should copy a single buffer into a smaller buffer") {
    val buffs = getBuffers(1)
    val target = ByteBuffer.allocate(2)

    assertEquals(BufferTools.copyBuffers(buffs, target), 2)
    assertEquals(target.position(), 2)
    assertEquals(buffs(0).position(), 0)
  }

  test("BufferTools.copyBuffers should copy multiple buffers int a larger buffer") {
    val buffs = getBuffers(2)
    val target = ByteBuffer.allocate(10)

    assertEquals(BufferTools.copyBuffers(buffs, target), 8)
    assertEquals(target.position(), 8)

    assert(buffs.forall(buff => buff.position() == 0))
  }

  test("BufferTools.copyBuffers should copy multiple buffers int a smaller buffer") {
    val buffs = getBuffers(2)

    {
      val target = ByteBuffer.allocate(2)

      assertEquals(BufferTools.copyBuffers(buffs, target), 2)
      assertEquals(target.position(), 2)

      assert(buffs.forall(buff => buff.position() == 0))
    }

    {
      val target = ByteBuffer.allocate(6)

      assertEquals(BufferTools.copyBuffers(buffs, target), 6)
      assertEquals(target.position(), 6)

      assert(buffs.forall(buff => buff.position() == 0))
    }
  }

  test("BufferTools.copyBuffers should handle null buffers in the array") {
    val buffs = getBuffers(2)
    buffs(0) = null

    val target = ByteBuffer.allocate(10)

    assertEquals(BufferTools.copyBuffers(buffs, target), 4)
    assertEquals(target.position(), 4)

    assertEquals(buffs(1).position(), 0)
  }

  test("BufferTools.fastForwardBuffers should fast forward an entire array") {
    val buffers = getBuffers(4)

    assert(BufferTools.fastForwardBuffers(buffers, 4 * 4))

    assert(buffers.forall(_.remaining == 0))
  }

  test("BufferTools.fastForwardBuffers should fast forward only part of an array") {
    val buffers = getBuffers(2)

    assert(BufferTools.fastForwardBuffers(buffers, 5))

    assertEquals(buffers(0).remaining, 0)
    assertEquals(buffers(1).remaining, 3)
  }

  test("BufferTools.fastForwardBuffers should fast forward past an array") {
    val buffers = getBuffers(2)

    assertEquals(BufferTools.fastForwardBuffers(buffers, 10), false)

    assert(buffers.forall(_.remaining == 0))
  }

  test("BufferTools.fastForwardBuffers should fast forward an array with null elements") {
    val buffers = getBuffers(2)
    buffers(0) = null

    assert(BufferTools.fastForwardBuffers(buffers, 2))

    assertEquals(buffers(1).remaining, 2)
  }

  test("BufferTools.areDirectOrEmpty should be true for a collection of direct buffers") {
    assert(BufferTools.areDirectOrEmpty(getDirect(4)))
  }

  test("BufferTools.areDirectOrEmpty should be true for a collection of nulls") {
    assert(BufferTools.areDirectOrEmpty(Array[ByteBuffer](null, null)))
  }

  test(
    "BufferTools.areDirectOrEmpty should be true for a collection of direct buffers with a null element") {
    val buffs = getDirect(4)
    buffs(3) = null
    assert(BufferTools.areDirectOrEmpty(buffs))
  }

  test(
    "BufferTools.areDirectOrEmpty should be false for a collection with a non-empty heap buffer in it") {
    val buffs = getDirect(4)
    buffs(3) = ByteBuffer.allocate(4)
    assertEquals(BufferTools.areDirectOrEmpty(buffs), false)
  }

  test(
    "BufferTools.areDirectOrEmpty should be true for a collection with an empty heap buffer in it") {
    val buffs = getDirect(4)
    buffs(3) = ByteBuffer.allocate(0)
    assert(BufferTools.areDirectOrEmpty(buffs))
  }

  private def getBuffers(count: Int): Array[ByteBuffer] = getBuffersBase(count, false)

  private def getDirect(count: Int): Array[ByteBuffer] = getBuffersBase(count, true)

  private def getBuffersBase(count: Int, direct: Boolean): Array[ByteBuffer] =
    (0 until count).map { _ =>
      val buffer = if (direct) ByteBuffer.allocateDirect(4) else ByteBuffer.allocate(4)
      buffer.putInt(4).flip()
      buffer
    }.toArray
}
