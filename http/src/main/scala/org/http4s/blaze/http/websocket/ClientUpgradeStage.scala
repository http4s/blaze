package org.http4s.blaze.http.websocket

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.Execution
import org.http4s.blaze.http.http_parser.Http1ClientParser
import org.http4s.blaze.pipeline.TailStage
import org.http4s.websocket.WebsocketHandshake

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}
import scala.concurrent.{Future, Promise}


class ClientUpgradeStage(target: String, host: String, continuation: Promise[Either[String, ByteBuffer]]) extends TailStage[ByteBuffer] {
  private val handshaker = WebsocketHandshake.clientHandshaker(host)

  private class UpgradeDecoder extends Http1ClientParser() {
    private var code: Int = 0
    private var reason: String = _

    private val headers = new ListBuffer[(String, String)]

    // True if parsing complete, false if needs more buffer
    def parseBuffer(buffer: ByteBuffer): Boolean = {
      if (!super.responseLineComplete() && !super.parseResponseLine(buffer)) {
        return false
      }

      if (!super.headersComplete() && !super.parseHeaders(buffer)) {
        return false
      }


      handshaker.checkResponse(headers.result()) match {
        case Right(()) => continuation.trySuccess(Right(buffer))
        case Left(s) => continuation.trySuccess(Left(s))
      }

      true
    }

    override protected def submitResponseLine(code: Int, reason: String, scheme: String,
                                              majorversion: Int, minorversion: Int): Unit = {
      this.code = code
      this.reason = reason
    }

    override protected def headerComplete(name: String, value: String): Boolean = {
      headers += name -> value
      true
    }
  }

  private val decoder = new UpgradeDecoder

  override def name: String = "ClientUpgradeStage"

  override protected def stageStartup(): Unit = {
    super.stageStartup()

    channelWrite(makeHandshakeBuffer())
      .flatMap(_ => channelRead())(Execution.directec)
      .onComplete {
        case Success(b) => handshakeLoop(b)
        case Failure(e) => continuation.tryComplete(Failure(e))
      }(Execution.directec)
  }

  private def handshakeLoop(buffer: ByteBuffer): Unit = {
    if (!decoder.parseBuffer(buffer)) {
      channelRead().onComplete {
        case Success(b) => handshakeLoop(b)
        case Failure(f) => continuation.tryFailure(f)
      }(Execution.trampoline)
    }
  }

  private def makeHandshakeBuffer(): ByteBuffer = {
    val sb = new StringBuilder
    sb.append("GET ")
      .append(target)
      .append("HTTP/1.1/r/n")

    handshaker.initHeaders.foreach { case (k,v ) =>
      sb.append(k)
        .append(": ")
        .append(v)
        .append("\r\n")
    }

    StandardCharsets.US_ASCII.encode(sb.result())
  }
}

