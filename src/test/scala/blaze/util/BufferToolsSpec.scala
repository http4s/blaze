package blaze.util

import org.scalatest.{Matchers, WordSpec}
import java.nio.ByteBuffer


/**
 * @author Bryce Anderson
 *         Created on 1/28/14
 */
class BufferToolsSpec extends WordSpec with Matchers {

  def b(i: Int = 1) = {
    val b = ByteBuffer.allocate(4)
    b.putInt(i).flip()
    b
  }

  "BufferTools" should {
    "discard null old buffers" in {
      val bb = b()
      BufferTools.concatBuffers(null, bb) should equal(bb)
    }

    "discard empty buffers" in {
      val b1 = b(); val b2 = b()
      b1.getInt()
      BufferTools.concatBuffers(b1, b2) should equal(b2)
    }

    "concat two buffers" in {
      val b1 = b(1); val b2 = b(2)
      val a = BufferTools.concatBuffers(b1, b2)
      a.remaining() should equal(8)
      a.getInt() should equal(1)
      a.getInt() should equal(2)
    }

    "append the result of one to the end of another if there is room" in {
      val b1 = ByteBuffer.allocate(9)
      b1.position(1)              // offset by 1 to simulated already having read a byte
      b1.putInt(1).flip().position(1)
      val b2 = b(2)

      val bb = BufferTools.concatBuffers(b1, b2)
      bb should equal(b1)
      bb.position() should equal(1)
      bb.getInt() should equal(1)
      bb.getInt() should equal(2)
    }

    "compact a buffer to fit the second" in {
      val b1 = ByteBuffer.allocate(8)
      b1.putInt(0).putInt(1).flip()
      b1.getInt() // Discard the first element
      val b2 = b(2)

      val bb = BufferTools.concatBuffers(b1, b2)
      bb should equal(b1)
      bb.getInt() should equal(1)
      bb.getInt() should equal(2)
    }
  }

}
