package blaze.pipeline.stages.http.websocket

import org.scalatest.{Matchers, WordSpec}

import blaze.pipeline.stages.http.websocket.WebSocketDecoder._
import blaze.pipeline.{TrunkBuilder, TailStage}
import blaze.pipeline.stages.SeqHead

import scala.concurrent.Await
import scala.concurrent.duration._
import java.net.ProtocolException

/**
 * @author Bryce Anderson
 *         Created on 1/19/14
 */
class FrameAggregatorSpec extends WordSpec with Matchers {

  case class Sequencer(frames: Seq[WebSocketFrame]) {
    val h = new TailStage[WebSocketFrame] {
      def name: String = "gatherer"
    }

    TrunkBuilder(new WSFrameAggregator).cap(h).base(new SeqHead(frames))

    def next = Await.result(h.channelRead(), 2.seconds)
  }

  "FrameAggregator" should {

    val fooFrames = Text("foo", false)::Continuation("bar".getBytes(), true)::Nil
    val barFrames = Binary("baz".getBytes, false)::Continuation("boo".getBytes(), true)::Nil

    val wControl = Text("foo", false)::Ping()::Continuation("bar".getBytes(), true)::Nil

    "Pass pure frames" in {
      val s = Sequencer(fooFrames:::Text("bar")::Nil)
      s.next should equal(Text("foobar"))
      s.next should equal(Text("bar"))
    }

    "Aggregate frames" in {
      Sequencer(fooFrames).next should equal(Text("foobar"))

      val s = Sequencer(fooFrames:::barFrames)
      s.next should equal(Text("foobar"))
      s.next should equal(Binary("bazboo".getBytes()))
    }

    "Allow intermingled control frames" in {
      val s = Sequencer(wControl)
      s.next should equal (Ping())
      s.next should equal (Text("foobar"))
    }

    "Give a ProtocolException on unheaded Continuation frame" in {
      a[ProtocolException] should be thrownBy Sequencer(Continuation(Array.empty, true)::Nil).next
    }

    "Give a ProtocolException on Head frame before Continuation end" in {
      val msgs = Text("foo", false)::Text("bar", true)::Nil
      a[ProtocolException] should be thrownBy Sequencer(msgs).next
    }
  }

}
