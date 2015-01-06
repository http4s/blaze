package org.http4s.blaze.pipeline.stages

import java.util.concurrent.atomic.AtomicInteger

import org.specs2.mutable._

import org.http4s.blaze.pipeline.Command._
import scala.concurrent.Future
import org.http4s.blaze.pipeline._
import org.http4s.blaze.util.Execution.trampoline

import scala.util.{Success, Failure}


class HubStageSpec extends Specification {

  case class Msg(k: Int, msg: String)

  val msgs = Msg(1, "one")::Msg(2, "two")::Nil

  // Just overwrite the abstract methods if we need them and assigns the EC to be a one that uses the current thread
  abstract class TestHub[I, O, K](f: () => LeafBuilder[O]) extends HubStage[I] {


    override def name: String = "TestHub"

    override type Attachment = Null
    override type Out = O
    override type Key = K

    def makeNode(i: Key): Option[Node] = super.makeNode(i, f(), null)

    override protected def onNodeWrite(node: Node, data: Seq[Out]): Future[Unit] = ???
    override protected def onNodeRead(node: Node, size: Int): Future[Out] = ???
    override protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit = ???
  }

  "HubStage" should {

    "Initialize" in {
      var started = false

      class Echo1 extends TailStage[Any] {
        def name = "Echo1"
        override protected def stageStartup(): Unit = {
          started = true
        }
      }

      class THub extends TestHub[Msg, Any, Int](() => LeafBuilder(new Echo1)) {
        override def name: String = "THub"
        override protected def stageStartup(): Unit = {
          makeNode(0).get.startNode()
          super.stageStartup()
        }
      }


      val h = new SeqHead(msgs)
      LeafBuilder(new THub).base(h)

      started must_== false

      h.inboundCommand(Connected)
      started must_== true
    }

    "Shutdown nodes" in {

      val closed = new AtomicInteger(0)
      val started = new AtomicInteger(0)

      class Echo2 extends TailStage[Any] {
        def name = "Echo2"
        override protected def stageShutdown(): Unit = {
          closed.incrementAndGet()
          super.stageShutdown()
        }

        override protected def stageStartup(): Unit = {
          super.stageStartup()
          started.incrementAndGet()
        }
      }

      class THub extends TestHub[Msg, Any, Int](() => LeafBuilder(new Echo2)) {
        override protected def stageStartup(): Unit = {

          makeNode(1).get.startNode()
          makeNode(2).get.startNode()

          super.stageStartup()
        }

        override protected def onNodeRead(node: Node, size: Int): Future[Out] =
          Future.failed(EOF)
      }

      val h = new SeqHead[Msg](List())
      LeafBuilder(new THub).base(h)

      closed.get() must_== 0
      h.inboundCommand(Connected)
      started.get() must_== 2
      closed.get() must_== 0

      h.inboundCommand(Disconnected)
      started.get() must_== 2
      closed.get() must_== 2
    }

    "Deal with node write requests" in {
      var written = 0
      var id = -1

      class Chatty extends TailStage[Int] {
        def name = "Chatty"
        override protected def stageStartup(): Unit = {
          channelWrite(1)
        }
      }

      class THub extends TestHub[Msg, Int, Int](() => LeafBuilder(new Chatty)) {
        override protected def stageStartup(): Unit = {
          makeNode(1).get.startNode()
          super.stageStartup()
        }

        override protected def onNodeWrite(node: Node, data: Seq[Int]): Future[Unit] = data match {
          case Seq(i) =>
            written += i
            id = node.key
            Future.successful(())
        }
      }

      val h = new SeqHead(msgs)
      LeafBuilder(new THub).base(h)

      written must_== 0
      id must_== -1
      h.inboundCommand(Connected)
      written must_== 1
      id must_== 1
    }

    "Deal with node read requests" in {
      var readreq = 0
      var id = -1

      class Chatty extends TailStage[Int] {
        def name = "Chatty"
        override protected def stageStartup(): Unit = {
          channelRead(1)
        }
      }

      class THub extends TestHub[Msg, Int, Int](() => LeafBuilder(new Chatty)) {
        override protected def stageStartup(): Unit = {
          makeNode(1).get.startNode()
          super.stageStartup()
        }

        override protected def onNodeRead(node: Node, size: Int): Future[Int] = {
          readreq = size
          id = node.key
          Future.successful(0)
        }
      }

      val h = new SeqHead(msgs)
      LeafBuilder(new THub).base(h)

      readreq must_== 0
      id must_== -1
      h.inboundCommand(Connected)
      readreq must_== 1
      id must_== 1
    }

    "Deal with node commands" in {
      var flushreq = 0
      var id = -1

      class Commanding extends TailStage[Int] {
        def name = "Chatty"
        override protected def stageStartup(): Unit = {
          sendOutboundCommand(Command.Flush)
        }
      }

      class THub extends TestHub[Msg, Int, Int](() => LeafBuilder(new Commanding)) {
        override protected def stageStartup(): Unit = {
          makeNode(1).get.startNode()
          makeNode(2).get.startNode()
          super.stageStartup()
        }

        override protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit = cmd match {
          case Command.Flush =>
            flushreq += 1
            id = node.key
        }
      }

      val h = new SeqHead(msgs)
      LeafBuilder(new THub).base(h)

      flushreq must_== 0
      id must_== -1
      h.inboundCommand(Connected)
      flushreq must_== 2
      id must_== 2
    }

    "Not replace existing nodes" in {
      var complete = 0

      class Commanding extends TailStage[Int] {
        def name = "Chatty"
      }

      class THub extends TestHub[Msg, Int, Int](() => LeafBuilder(new Commanding)) {
        override protected def stageStartup(): Unit = {
          super.stageStartup()
          if (makeNode(1).nonEmpty) complete += 1
          if (makeNode(1).nonEmpty) complete += 1
        }
      }

      val h = new SeqHead(msgs)
      LeafBuilder(new THub).base(h)

      complete must_== 0
      h.inboundCommand(Connected)
      complete must_== 1
    }
//
//    "Perform an echo test" in {
//      class Echo extends TailStage[Msg] {
//        def name: String = "EchoTest"
//
//        override protected def stageStartup(): Unit = {
//          readLoop()
//        }
//
//        private def readLoop(): Unit = channelRead().onComplete {
//          case Success(msg) =>
//            channelWrite(Msg(msg.k, "Echoing: " + msg.msg))
//              .onSuccess{ case _ => readLoop() }
//
//          case Failure(EOF) => logger.debug("Received EOF")
//          case _ => sys.error("Shouldn't get here!")
//        }
//      }
//
//      class THub extends TestHub[Msg, Msg, Int](() => LeafBuilder(new Echo)) {
//
//        override protected def stageStartup(): Unit = {
//          super.stageStartup()
//          reqLoop()
//        }
//
//        private def reqLoop(): Unit = channelRead().onComplete {
//          case Success(msg) =>
//            val k = msg.k
//            getNode(k) match {
//              case Some(node) => node.sendMsg(msg)
//              case None =>
//                val n = makeNode(k)
//                n.sendMsg(msg)
//            }
//
//            reqLoop()
//
//          case Failure(EOF) =>
//            logger.debug("Finished.")
//            closeAllNodes()
//
//          case Failure(t)   => throw t
//        }
//
//        override protected def onNodeRead(node: Node, size: Int): Unit = {}
//
//        override protected def onNodeWrite(node: Node, data: Seq[Msg]): Future[Unit] = channelWrite(data)
//
//        override protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit = {
//          cmd match {
//            case Disconnect => removeNode(node)
//            case _ => sendOutboundCommand(cmd)
//          }
//        }
//      }
//
//      val h = new SeqHead(msgs)
//      LeafBuilder(new THub).base(h)
//
//      h.inboundCommand(Connected)
//      h.inboundCommand(Disconnected)
//
//      h.results should_== Seq(Msg(1, "Echoing: one"), Msg(2, "Echoing: two"))
//    }
  }
}
