package org.http4s.blaze.http.websocket

import org.specs2.mutable._

import org.http4s.websocket.WebsocketBits._
import org.http4s.blaze.pipeline.{TrunkBuilder, TailStage}
import org.http4s.blaze.pipeline.stages.SeqHead

import scala.concurrent.Await
import scala.concurrent.duration._
import java.net.ProtocolException


class FrameAggregatorSpec extends Specification {

  case class Sequencer(frames: Seq[WebSocketFrame]) {
    val h = new TailStage[WebSocketFrame] {
      def name: String = "gatherer"
    }

    TrunkBuilder(new WSFrameAggregator).cap(h).base(new SeqHead(frames))

    def next = Await.result(h.channelRead(), 10.seconds)
  }

  "FrameAggregator" should {

    val fooFrames = Text("foo", false)::Continuation("bar".getBytes(), true)::Nil
    val barFrames = Binary("baz".getBytes, false)::Continuation("boo".getBytes(), true)::Nil

    val wControl = Text("foo", false)::Ping()::Continuation("bar".getBytes(), true)::Nil

    "Pass pure frames" in {
      val s = Sequencer(fooFrames:::Text("bar")::Nil)
      s.next should_== Text("foobar")
      s.next should_== Text("bar")
    }

    "Aggregate frames" in {
      Sequencer(fooFrames).next should_== Text("foobar")

      val s = Sequencer(fooFrames:::barFrames)
      s.next should_== Text("foobar")
      s.next should_== Binary("bazboo".getBytes())
    }

    "Allow intermingled control frames" in {
      val s = Sequencer(wControl)
      s.next should_== Ping()
      s.next should_== Text("foobar")
    }

    "Give a ProtocolException on unheaded Continuation frame" in {
      Sequencer(Continuation(Array.empty, true)::Nil).next should throwA[ProtocolException]
    }

    "Give a ProtocolException on Head frame before Continuation end" in {
      val msgs = Text("foo", false)::Text("bar", true)::Nil
      Sequencer(msgs).next should throwA[ProtocolException]
    }
  }

}
