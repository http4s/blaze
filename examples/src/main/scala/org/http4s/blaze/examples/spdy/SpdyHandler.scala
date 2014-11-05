package org.http4s.blaze.examples.spdy

import org.http4s.blaze.pipeline.{TailStage, Command}
import org.http4s.blaze.http.spdy._
import javax.net.ssl.SSLEngine
import scala.util.{Failure, Success}
import org.http4s.blaze.pipeline.Command.EOF

import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.ByteBuffer
import org.http4s.blaze.http.spdy.SynReplyFrame
import scala.util.Success
import org.http4s.blaze.http.spdy.DataFrame
import scala.util.Failure
import org.http4s.blaze.http.spdy.SynStreamFrame
import org.log4s.getLogger

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */
class SpdyHandler(eng: SSLEngine) extends TailStage[SpdyFrame] {
  private[this] val logger = getLogger

  def name: String = "SpdyStage"

  override protected def stageStartup(): Unit = {
    logger.info("Starting SPDYHandler")
    readLoop()
  }

  def readLoop(): Unit = {
    channelRead().onComplete {

      case Success(req: SynStreamFrame) =>

        val path = req.headers(":path").head

        logger.info(s"Got full request $req")

        if (path == "/favicon.ico") {
          logger.info("Sending icon reply.")
          channelWrite(notFound(req.streamid)).onComplete(_ => readLoop())
        } else {
          channelWrite(response(req)).onComplete( _ => readLoop() )
        }

      case Success(ping: PingFrame) =>
        logger.info("Replying to PING frame")
        channelWrite(PingFrame(ping.id)).onComplete(_ => readLoop())

      case Success(frame) =>
        logger.info("Got spdy frame: " + frame)
        readLoop()

      case Failure(EOF) =>
        logger.info("End of spdy stream")
        stageShutdown()

      case Failure(t) =>
        logger.error(t)("Stream failure. Shutting down")
        sendOutboundCommand(Command.Disconnect)
        stageShutdown()
    }
  }

  def notFound(id: Int): SpdyFrame = {
    val headers = Map(":status" -> Seq("404 Not Found"),
              ":version" -> Seq("HTTP/1.1"),
              ":scheme" -> Seq("https"))

    SynReplyFrame(id, headers, true)
  }

  def response(req: SynStreamFrame): Seq[SpdyFrame] = {

    val path = req.headers(":path").head

    val headers = Map(":status" -> Seq("200 OK"),
                      ":version" -> Seq("HTTP/1.1"),
                      ":scheme" -> Seq("https"))

    val head = new SynReplyFrame(req.streamid, headers, false)
    val data = DataFrame(ByteBuffer.wrap(s"Hello world\n$path".getBytes()), req.streamid, true)
    val goaway = GoAwayFrame(req.streamid, GoAwayCode.OK)

    Array(head, data)
  }

  override protected def stageShutdown(): Unit = super.stageShutdown()
}
