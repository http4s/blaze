package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.{SSLEngineResult, SSLEngine}
import SSLEngineResult.HandshakeStatus._

import org.http4s.blaze.pipeline.Command.Connected
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.{GenericSSLContext, BufferTools}
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.duration._
import scala.concurrent._

class SSLStageSpec extends Specification with NoTimeConversions {

  def debug = false

  "SSLStage in server mode" should {
    testBattery(mkClientServerEngines())
  }

  "SSLStage in client mode" should {
    testBattery(mkClientServerEngines().swap)
  }

  /////////////// The battery of tests for both client and server ////////////////////
  def testBattery(mkClientServerEngines: => (SSLEngine, SSLEngine)) = {
    "Transcode a single buffer" in {
      val (headEng, stageEng) = mkClientServerEngines
      val head = new SSLSeqHead(Seq(mkBuffer("Foo")), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.ready(tail.startLoop(), 10.seconds)

      head.results.length must beGreaterThan(0)
      BufferTools.mkString(head.results) must_== "Foo"
    }

    "Split large buffers" in {
      val (headEng, stageEng) = mkClientServerEngines
      val s = "Fo"*(stageEng.getSession.getPacketBufferSize*0.75).toInt

      /* The detection of splitting the buffer is seen by checking the write
       * output: if its flushing, the output should only be single buffers for
       * a small flush limits. This could break with changes to the SSLStage
       * algorithm
       */
      class TestStage extends SSLSeqHead(Seq(mkBuffer(s)), headEng) {
        var multipleWrite = false
        override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
          if (data.length > 1) multipleWrite = true
          super.writeRequest(data)
        }
      }

      val head = new TestStage

      val tail = new MapTail[ByteBuffer](b => BufferTools.concatBuffers(b, b.duplicate()))
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng, maxWrite = 100))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.ready(tail.startLoop(), 20.seconds)

      val r = BufferTools.mkString(head.results)
      head.multipleWrite must_== false
      r must_== s + s
    }

    "Transcode multiple single byte buffers" in {

      val (headEng, stageEng) = mkClientServerEngines

      val strs = 0 until 10 map (_.toString)
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.result(tail.startLoop(), 20.seconds)

      if (debug) println(head.results)

      BufferTools.mkString(head.results) must_== strs.mkString("")
    }

    "Transcode multiple buffers" in {

      val (headEng, stageEng) = mkClientServerEngines

      val strs = 0 until 10 map { i => "Buffer " + i + ", " }
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.result(tail.startLoop(), 20.seconds)

      if (debug) println(head.results)

      BufferTools.mkString(head.results) must_== strs.mkString("")
    }

    "Handle empty buffers gracefully" in {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = 0 until 10 map { i => "Buffer " + i + ", " }
      val head = new SSLSeqHead(strs.flatMap(s => Seq(mkBuffer(s), BufferTools.emptyBuffer)), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.result(tail.startLoop(), 20.seconds)

      if (debug) println(head.results)

      BufferTools.mkString(head.results) must_== strs.mkString("")
    }

    "Survive aggressive handshaking" in {

      val (headEng, stageEng) = mkClientServerEngines

      val strs = 0 until 100 map { i => "Buffer " + i + ", " }
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng, 2)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.result(tail.startLoop(), 20.seconds)

      if (debug) println(head.results)

      BufferTools.mkString(head.results) must_== strs.mkString("")
    }

    "Survive aggressive handshaking with single byte buffers" in {

      val (headEng, stageEng) = mkClientServerEngines

      val strs = 0 until 100 map (_.toString)
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng, 2)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.result(tail.startLoop(), 20.seconds)

      if (debug) println(head.results)

      BufferTools.mkString(head.results) must_== strs.mkString("")
    }

    "Survive aggressive handshaking with empty buffers" in {

      val (headEng, stageEng) = mkClientServerEngines

      val strs = 0 until 10 map { i => "Buffer " + i + ", " }
      val head = new SSLSeqHead(strs.flatMap(s => Seq(mkBuffer(s), BufferTools.emptyBuffer)), headEng, 2)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)
      Await.result(tail.startLoop(), 20.seconds)

      if (debug) println(head.results)

      BufferTools.mkString(head.results) must_== strs.mkString("")
    }
  }

  def mkBuffer(str: String): ByteBuffer = ByteBuffer.wrap(str.getBytes())
  
  def mkClientServerEngines(): (SSLEngine, SSLEngine) = {
    val clientEng = GenericSSLContext.clientSSLContext().createSSLEngine()
    clientEng.setUseClientMode(true)

    val serverEng = GenericSSLContext.serverSSLContext().createSSLEngine()
    serverEng.setUseClientMode(false)
    
    (clientEng, serverEng)
  }

  /** This stage assumes the data coming from Tail is secured
    *
    * It was a pain in the end to make this thing which is really only useful for testing
    * @param data collection of ByteBuffers to hold in the Head
    * @param engine SSLEngine to use for encryption
    * @param handshakeInterval interval with which to induce another handshake
    */
  class SSLSeqHead(data: Seq[ByteBuffer], engine: SSLEngine, handshakeInterval: Int = -1) extends SeqHead[ByteBuffer](data) {

    private val lock = new AnyRef
    private val maxNetSize = engine.getSession.getPacketBufferSize
    private var handShakeBuffer: ByteBuffer = null
    private var readBuffer: ByteBuffer = null
    private var writeBuffer: ByteBuffer = null

    private var count = 0

    private def handShake(): Unit = lock.synchronized {
      if (debug) println("Handshaking: " + engine.getHandshakeStatus)

      engine.getHandshakeStatus match {
        case NOT_HANDSHAKING | FINISHED => ()

        case NEED_TASK =>
          var t = engine.getDelegatedTask
          while(t != null) {
            t.run()
            t = engine.getDelegatedTask
          }
          handShake()

        case NEED_WRAP =>
          val o = BufferTools.allocate(maxNetSize)
          val r = engine.wrap(BufferTools.emptyBuffer, o)
          if (debug) println(r)
          o.flip()
          assert(handShakeBuffer == null)
          handShakeBuffer = o

        case NEED_UNWRAP => ()
      }
    }

    private def checkHandshaking(): Unit = {
      if (handshakeInterval > 0) { // Induce handshaking.
        count += 1
        if (count % handshakeInterval == 0) {
          if (debug) println("Inducing handshake")
          engine.beginHandshake()
        }
      }
    }

    override def readRequest(size: Int): Future[ByteBuffer] = lock.synchronized {
      if (debug) println("ReadReq: " + engine.getHandshakeStatus)
      def go(buffer: ByteBuffer): Future[ByteBuffer] = {
        try {
          val o = BufferTools.allocate(maxNetSize)
          val r = engine.wrap(buffer, o)
          o.flip()

          // Store any left over buffer
          if (buffer.hasRemaining) {
            assert (readBuffer == null)
            readBuffer = buffer
          }

          if (debug) println("Go in readRequest: " + r)
          r.getHandshakeStatus match {
            case NOT_HANDSHAKING =>
              checkHandshaking()
              Future.successful(o)

            case status =>
              if (debug) println("Need to handshake: " + o)

              if (o.hasRemaining) Future.successful(o)
              else {
                handShake()
                Future.successful(BufferTools.emptyBuffer)
              }
          }
        } catch {
          case t: Throwable => println(t); Future.failed(t)
        }
      }

      if (handShakeBuffer != null) {
        val b = handShakeBuffer
        handShakeBuffer = null
        Future.successful(b)
      }
      else {
        if (readBuffer != null) {
          val b = readBuffer
          readBuffer = null
          go(b)
        }
        else super.readRequest(size).flatMap(go)
      }
    }

    override def writeRequest(data: ByteBuffer): Future[Unit] = lock.synchronized {

      def go(data: ByteBuffer): Future[Unit] = {
        try {
          val o = BufferTools.allocate(maxNetSize)
          val r = engine.unwrap(data, o)
          if (debug) println("Write Go: " + r)
          o.flip()

          r.getHandshakeStatus match {
            case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
              checkHandshaking()

              if (data.hasRemaining) {
                if (r.getStatus == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                  assert(writeBuffer == null)
                  writeBuffer = data
                  super.writeRequest(o)
                }
                else super.writeRequest(o).flatMap{ _ => writeRequest(data) }
              }
              else super.writeRequest(o)

            case status =>
              val f = {
                if (o.hasRemaining) {
                  super.writeRequest(o).flatMap(_ => writeRequest(data))
                }
                else {
                  if (data.hasRemaining) {
                    assert(writeBuffer == null)
                    writeBuffer = data
                  }
                  Future.successful(())
                }
              }

              f.flatMap { _ =>
                handShake()
                if (data.hasRemaining) go(data)
                else Future.successful(())
              }
          }
        } catch { case t: Throwable => Future.failed(t) }
      }

      val b = {
        val b = BufferTools.concatBuffers(writeBuffer, data)
        writeBuffer = null
        b
      }

      go(b)
    }
  }
}
