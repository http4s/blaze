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

import javax.net.ssl.{SSLEngine, SSLEngineResult}
import SSLEngineResult.HandshakeStatus._
import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.Command.Connected
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.{BufferTools, Execution, FutureUnit, GenericSSLContext}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class SSLStageSuite extends BlazeTestSuite {
  private implicit def ec: ExecutionContext = Execution.trampoline

  private def debug = false

  testBattery("A SSLStage in server mode", mkClientServerEngines())

  testBattery("A SSLStage in client mode", mkClientServerEngines().swap)

  // The battery of tests for both client and server
  private def testBattery(
      testSuitePrefix: String,
      mkClientServerEngines: => (SSLEngine, SSLEngine)) = {
    test(testSuitePrefix + " should transcode a single buffer") {
      val (headEng, stageEng) = mkClientServerEngines
      val head = new SSLSeqHead(Seq(mkBuffer("Foo")), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ <- assertFutureBoolean(Future(head.results.nonEmpty))
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), "Foo")
      } yield ()
    }

    test(testSuitePrefix + " should split large buffers") {
      val (headEng, stageEng) = mkClientServerEngines
      val s = "Fo" * (stageEng.getSession.getPacketBufferSize * 0.75).toInt

      /* The detection of splitting the buffer is seen by checking the write
       * output: if its flushing, the output should only be single buffers for
       * a small flush limits. This could break with changes to the SSLStage
       * algorithm
       */
      class TestStage extends SSLSeqHead(Seq(mkBuffer(s)), headEng) {
        var multipleWrite = false
        override def writeRequest(data: collection.Seq[ByteBuffer]): Future[Unit] = {
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

      assertFuture_(tail.startLoop()).flatMap(_ =>
        assertFuture(
          for {
            r <- Future(BufferTools.mkString(head.results))
            h <- Future(head.multipleWrite)
          } yield r -> h,
          s + s -> false))
    }

    test(testSuitePrefix + " should transcode multiple single byte buffers") {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = (0 until 10).map(_.toString)
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ = if (debug) println(head.results)
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), strs.mkString(""))
      } yield ()
    }

    test(testSuitePrefix + " should transcode multiple buffers") {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = (0 until 10).map(i => "Buffer " + i + ", ")
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ = if (debug) println(head.results)
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), strs.mkString(""))
      } yield ()
    }

    test(testSuitePrefix + " should handle empty buffers gracefully") {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = (0 until 10).map(i => "Buffer " + i + ", ")
      val head =
        new SSLSeqHead(strs.flatMap(s => Seq(mkBuffer(s), BufferTools.emptyBuffer)), headEng)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ = if (debug) println(head.results)
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), strs.mkString(""))
      } yield ()
    }

    test(testSuitePrefix + " should survive aggressive handshaking") {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = (0 until 100).map(i => "Buffer " + i + ", ")
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng, 2)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ = if (debug) println(head.results)
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), strs.mkString(""))
      } yield ()
    }

    test(testSuitePrefix + " should survive aggressive handshaking with single byte buffers") {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = (0 until 100).map(_.toString)
      val head = new SSLSeqHead(strs.map(mkBuffer), headEng, 2)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ = if (debug) println(head.results)
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), strs.mkString(""))
      } yield ()
    }

    test(testSuitePrefix + " should survive aggressive handshaking with empty buffers") {
      val (headEng, stageEng) = mkClientServerEngines

      val strs = (0 until 10).map(i => "Buffer " + i + ", ")
      val head =
        new SSLSeqHead(strs.flatMap(s => Seq(mkBuffer(s), BufferTools.emptyBuffer)), headEng, 2)
      val tail = new EchoTail[ByteBuffer]
      LeafBuilder(tail)
        .prepend(new SSLStage(stageEng))
        .base(head)

      head.sendInboundCommand(Connected)

      for {
        _ <- assertFuture_(tail.startLoop())
        _ = if (debug) println(head.results)
        _ <- assertFuture(Future(BufferTools.mkString(head.results)), strs.mkString(""))
      } yield ()
    }
  }

  private def mkBuffer(str: String): ByteBuffer =
    ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8))

  private def mkClientServerEngines(): (SSLEngine, SSLEngine) = {
    val clientEng = GenericSSLContext.clientSSLContext().createSSLEngine()
    clientEng.setUseClientMode(true)

    val serverEng = GenericSSLContext.serverSSLContext().createSSLEngine()
    serverEng.setUseClientMode(false)

    (clientEng, serverEng)
  }

  /** This stage assumes the data coming from Tail is secured
    *
    * It was a pain in the end to make this thing which is really only useful for testing
    * @param data
    *   collection of ByteBuffers to hold in the Head
    * @param engine
    *   SSLEngine to use for encryption
    * @param handshakeInterval
    *   interval with which to induce another handshake
    */
  class SSLSeqHead(data: Seq[ByteBuffer], engine: SSLEngine, handshakeInterval: Int = -1)
      extends SeqHead[ByteBuffer](data) {
    private val lock = new AnyRef
    private val maxNetSize = engine.getSession.getPacketBufferSize
    private var handShakeBuffer: ByteBuffer = null
    private var readBuffer: ByteBuffer = null
    private var writeBuffer: ByteBuffer = null

    private var count = 0

    private def handShake(): Unit =
      lock.synchronized {
        if (debug) println("Handshaking: " + engine.getHandshakeStatus)

        engine.getHandshakeStatus match {
          case NOT_HANDSHAKING | FINISHED => ()

          case NEED_TASK =>
            var t = engine.getDelegatedTask
            while (t != null) {
              t.run()
              t = engine.getDelegatedTask
            }
            handShake()

          case NEED_WRAP =>
            val o = ByteBuffer.allocateDirect(maxNetSize)
            val r = engine.wrap(BufferTools.emptyBuffer, o)
            if (debug) println(r)
            o.flip()
            // assert(handShakeBuffer == null)
            handShakeBuffer = o

          // wildcard case includes NEED_UNWRAP, but also NEED_UNWRAP_AGAIN which is new in JDK 9.
          // need wildcard to be source-compatible and exhaustiveness-warning-free on both 8 and 9
          case _ => ()
        }
      }

    private def checkHandshaking(): Unit =
      if (handshakeInterval > 0) { // Induce handshaking.
        count += 1
        if (count % handshakeInterval == 0) {
          if (debug) println("Inducing handshake")
          engine.beginHandshake()
        }
      }

    override def readRequest(size: Int): Future[ByteBuffer] =
      lock.synchronized {
        if (debug) println("ReadReq: " + engine.getHandshakeStatus)
        def go(buffer: ByteBuffer): Future[ByteBuffer] =
          try {
            val o = ByteBuffer.allocate(maxNetSize)
            val r = engine.wrap(buffer, o)
            o.flip()

            // Store any left over buffer
            if (buffer.hasRemaining) {
              assert(readBuffer == null)
              readBuffer = buffer
            }

            if (debug) println("Go in readRequest: " + r)
            r.getHandshakeStatus match {
              case NOT_HANDSHAKING =>
                checkHandshaking()
                Future.successful(o)

              case _ =>
                if (debug) println("Need to handshake: " + o)

                if (o.hasRemaining) Future.successful(o)
                else {
                  handShake()
                  Future.successful(BufferTools.emptyBuffer)
                }
            }
          } catch {
            case NonFatal(t) => println(t); Future.failed(t)
          }

        if (handShakeBuffer != null) {
          val b = handShakeBuffer
          handShakeBuffer = null
          Future.successful(b)
        } else if (readBuffer != null) {
          val b = readBuffer
          readBuffer = null
          go(b)
        } else super.readRequest(size).flatMap(go)
      }

    override def writeRequest(data: ByteBuffer): Future[Unit] =
      lock.synchronized {
        def go(data: ByteBuffer): Future[Unit] =
          try {
            //
            val o = ByteBuffer.allocate(maxNetSize)
            val r = engine.unwrap(data, o)
            if (debug) println("Write Go: " + r)
            o.flip()

            r.getHandshakeStatus match {
              case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                checkHandshaking()

                if (data.hasRemaining)
                  if (r.getStatus == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    assert(writeBuffer == null)
                    writeBuffer = data
                    super.writeRequest(o)
                  } else
                    super.writeRequest(o).flatMap(_ => writeRequest(data))
                else super.writeRequest(o)

              case _ =>
                val f =
                  if (o.hasRemaining)
                    super.writeRequest(o).flatMap(_ => writeRequest(data))
                  else {
                    if (data.hasRemaining)
                      // assert(writeBuffer == null)
                      writeBuffer = data
                    FutureUnit
                  }

                f.flatMap { _ =>
                  handShake()
                  if (data.hasRemaining) go(data)
                  else FutureUnit
                }
            }
          } catch { case NonFatal(t) => Future.failed(t) }

        val b = {
          val b = BufferTools.concatBuffers(writeBuffer, data)
          writeBuffer = null
          b
        }

        go(b)
      }
  }
}
