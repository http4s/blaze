package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpClientSession.{ReleaseableResponse, Status}
import org.http4s.blaze.http._
import org.http4s.blaze.http.http1.client.Http1ClientCodec.EncodedPrelude
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

// TODO: we're totally logging all sorts of headers in here, which is bad for security
private[http] class Http1ClientStage(config: HttpClientConfig)
  extends TailStage[ByteBuffer] with Http1ClientSession {
  import Http1ClientStage._

  def name: String = "Http1ClientStage"

  @volatile private var state: State = Unconnected

  private[this] implicit def ec = Execution.trampoline
  private[this] val codec = new Http1ClientCodec(config)
  private[this] val stageLock: Object = codec  // No need for another object to lock on...

  // the dispatchId identifies each dispatch so that if the reader of the response is stored
  // and attempted to use later, the `dispatchId` will be different and the read call can return
  // an error as opposed to corrupting the session state.
  private[this] var dispatchId = 0L

  // ---------------------------------------------------------------------------------

  override def status: Status = {
    val s = stageLock.synchronized { state }
    s match {
      case Running(true, true) => HttpClientSession.Ready
      case Running(_, _) | Unconnected => HttpClientSession.Busy
      case Closed(_) => HttpClientSession.Closed
    }
  }

  // TODO: we should put this on a timer...
  override def close(within: Duration): Future[Unit] = {
    val sendDisconnect = stageLock.synchronized {
      state match {
        case Closed(_) => false
        case _ =>
          stageShutdown()
          true
      }
    }

    if (sendDisconnect) sendOutboundCommand(Command.Disconnect)
    Future.successful(())
  }

  // Entry method which, on startup, sends the request and attempts to parse the response
  override protected def stageStartup(): Unit = stageLock.synchronized {
    if (state == Unconnected) {
      super.stageStartup()
      state = Running(true, true)
    }
    else illegalState("stageStartup", state)
  }

  override protected def stageShutdown(): Unit = stageLock.synchronized {
    super.stageShutdown()
    if (!state.isInstanceOf[Closed]) {
      state = Closed(EOF)
    }
  }

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = stageLock.synchronized {
    state match {
      case r@Running(true, true) =>
        logger.debug("Initiating dispatch cycle")

        codec.reset()
        dispatchId += 1

        r.readChannelClear = false  // we are no longer idle, and the read/write channels
        r.writeChannelClear = false // must be considered contaminated.

        // Remember which dispatch we are in
        val thisDispatchId = dispatchId

        // Write the request to the wire
        launchWriteRequest(request).onComplete {
          case Success(_) => stageLock.synchronized {
            state match {
              case r@Running(_, _) if dispatchId == thisDispatchId =>
                logger.debug(s"Successfully finished writing request $request")
                r.writeChannelClear = true

              case _ => // nop
            }
          }

          case Failure(ex) =>
            logger.debug(ex)(s"Failed to write request $request")
            handleError("initial request write", ex)
        }

        val p = Promise[ReleaseableResponse]
        receiveResponse(BufferTools.emptyBuffer, p)
        p.future

      case state =>
        illegalState("initial dispatch", state)
    }
  }

  private[this] class ReleaseableResponseImpl(code: Int, status: String, headers: Headers, body: BodyReader)
    extends ClientResponse(code, status, headers, body) with ReleaseableResponse {
    override def release(): Unit = body.discard()
  }

  private[this] def receiveResponse(buffer: ByteBuffer, p: Promise[ReleaseableResponse]): Unit = {
    // We juggle the `null` to allow us to satisfy the promise outside of the lock
    val response: ReleaseableResponse = try stageLock.synchronized {
      logger.debug(s"Receiving response. $buffer, :\n${bufferAsString(buffer)}\n")
      if (!buffer.hasRemaining) {
        channelReadThenReceive(p)
        null
      } else if (!codec.preludeComplete && codec.parsePrelude(buffer)) {
        val prelude = codec.getResponsePrelude
        logger.debug(s"Finished getting prelude: $prelude")
        val body = getBodyReader(buffer)
        new ReleaseableResponseImpl(prelude.code, prelude.status, prelude.headers, body)
      } else {
        logger.debug(s"State: $buffer, ${codec.preludeComplete}, ${codec.responseLineComplete}, ${codec.headersComplete}")
        channelReadThenReceive(p)
        null
      }
    } catch {
      case NonFatal(t) =>
        closeNow().onComplete { _ => p.tryFailure(t) }
        null
    }

    if (response != null) {
      p.success(response)
      ()
    }
  }

  // Must be called from within the stage lock
  private[this] def channelReadThenReceive(p: Promise[ReleaseableResponse]): Unit = {
    state match {
      case Running(_, false) => channelRead().onComplete {
        case Success(buffer) => receiveResponse(buffer, p)
        case Failure(ex) =>
          handleError("channelReadThenReceive", ex)
          p.tryFailure(ex)
      }

      case Closed(ex) => p.tryFailure(ex); ()
      case state => illegalState("channelReadThenReceive", state)
    }
  }

  // BodyReader //////////////////////////////////////////////////////////////////////////

  private class ClientBodyReader(private[this] var buffer: ByteBuffer) extends BodyReader {
    // Acquired on creation! What a good deal.
    private[this] val myDispatchId = dispatchId
    private[this] var closedException: Throwable = null

    // must be called from within the stages `lock`
    private def validDispatch: Boolean = myDispatchId == dispatchId

    override def discard(): Unit = stageLock.synchronized {
      if (closedException == null) {
        closedException = EOF

        if (validDispatch) {
          // We need to try and burn through any remaining buffer
          // to see if we can put the parser in a sane state to perform
          // another dispatch, otherwise we need to kill the session.
          while (buffer.hasRemaining && !codec.contentComplete()) {
            val _ = codec.parseData(buffer)
          }

          state match {
            case r: Running =>
              r.readChannelClear = codec.contentComplete()
            case _ => // nop
          }
        }
      }
    }

    override def isExhausted: Boolean = stageLock.synchronized {
      closedException != null || !validDispatch
    }

    override def apply(): Future[ByteBuffer] = {
      val p = Promise[ByteBuffer]
      parseBody(p)
      p.future
    }

    private[this] def parseBody(p: Promise[ByteBuffer]): Unit = {
      val futureResult: Try[ByteBuffer] = tryParseBuffer()
      if (futureResult == null) readAndParseBody(p)
      else {
        p.complete(futureResult)
        ()
      }
    }

    private[this] def readAndParseBody(p: Promise[ByteBuffer]): Unit = {
      channelRead().onComplete {
        case Success(b) =>
          stageLock.synchronized {
            buffer = b
          }
          parseBody(p)

        case Failure(ex) =>
          stageLock.synchronized {
            closedException = ex
          }

          p.failure(ex)
      }
    }

    // WARNING: will emit `null` to signal 'needs more data'
    // Attempts to parse data kept in the `buffer` field into a body chunk
    private[this] def tryParseBuffer(): Try[ByteBuffer] = {
      try stageLock.synchronized {
        if (closedException == EOF) {
          Success(BufferTools.emptyBuffer)
        } else if (closedException != null) {
          Failure(closedException)
        } else {
          logger.debug(
            s"ParseBody[$buffer, chunking: ${codec.isChunked}, " +
              s"complete: ${codec.contentComplete()}, buffer: $buffer, state: $state]")

          if (!validDispatch) Failure(EOF)
          else state match {
            case Closed(ex) => Failure(ex)
            case Running(_, false) =>
              val out = codec.parseData(buffer)
              // We check if we're done and shut down if appropriate
              // If we're not done, we need to guard against sending
              // an empty `ByteBuffer` since that is our 'EOF' signal.
              if (codec.contentComplete()) {
                discard() // closes down our parser
                if (!buffer.hasRemaining) Success(out)
                else {
                  // We're not in a good state if we have finished parsing the response
                  // but still have some data. That is a sign that our session is probably
                  // corrupt so we should fail the BodyReader even though we technically
                  // have enough data.
                  closeNow()
                  Failure(new IllegalStateException(
                    s"HTTP1 client parser found in corrupt state: still have ${buffer.remaining()} data " +
                      s"after complete dispatch"
                  ))
                }
              }
              else if (out.hasRemaining) Success(out)
              else null // need more data

            case state => illegalState("parseBody", state)
          }
        }
      } catch { case NonFatal(t) =>
        closeNow()
        Failure(t)
      }
    }
  }

  // BodyReader //////////////////////////////////////////////////////////////////////////

  // Must be called from within the lock
  private[this] def getBodyReader(buffer: ByteBuffer): BodyReader = {
    if (codec.contentComplete()) {
      state match {
        case r@Running(_, false) => r.readChannelClear = true
        case _ => () // NOOP
      }
      BodyReader.EmptyBodyReader
    } else {
      logger.debug("Content is not complete. Getting body reader.")
      new ClientBodyReader(buffer)
    }
  }

  /** The returned Future is satisfied once the entire request has been written
    * to the wire. Any errors that occur during the write process, including those
    * that come from the request body, will result in the failed Future.
    */
  private[this] def launchWriteRequest(request: HttpRequest): Future[Unit] = {
    request.body().flatMap { firstChunk =>
      val hasBody = firstChunk.hasRemaining
      val EncodedPrelude(requestBuffer, encoder) = stageLock.synchronized {
        codec.encodeRequestPrelude(request, hasBody)
      }
      val p = Promise[Unit]
      channelWrite(requestBuffer).onComplete {
        case Failure(t) =>
          // Need to make sure we discard the body if we fail to write
          try request.body.discard()
          finally {
            p.tryFailure(t)
            ()
          }

        case Success(_) if !hasBody => p.trySuccess(())
        case Success(_) =>
          p.completeWith(encodeWithBody(firstChunk, request.body, encoder))

      }
      p.future
    }
  }

  private[this] def encodeWithBody(firstChunk: ByteBuffer, body: BodyReader, encoder: Http1BodyEncoder): Future[Unit] = {

    // This would be much nicer as a tail recursive loop, but that would
    // cause issues on Scala < 2.12 since it's Future impl isn't tail rec.
    val p = Promise[Unit]

    def writeLoop(): Unit = {
      body().onComplete {
        case Success(b) if !b.hasRemaining =>
          val last = encoder.finish()
          if (last.hasRemaining) p.completeWith(channelWrite(last))
          else p.success(())

        case Success(b) =>
          channelWrite(encoder.encode(b)).onComplete {
            case Success(_) => writeLoop()
            case Failure(err) =>
              // Ensure we close any resources associated with the body
              try body.discard()
              finally {
                p.tryFailure(err)
                ()
              }
          }
        case Failure(err) =>  p.tryFailure(err)
      }
    }

    channelWrite(encoder.encode(firstChunk))
      .onComplete {
        case Success(_) =>
          writeLoop()
        case Failure(e) =>
          try body.discard()
          finally {
            p.tryFailure(e)
            ()
          }
      }

    p.future
  }

  // Generally shuts things down. These may be normal errors
  private def handleError(phase: String, err: Throwable): Unit = stageLock.synchronized {
    logger.debug(err)(s"Phase $phase resulted in an error. Current state: $state")
    state = Closed(err)
    stageShutdown()
  }

  private def illegalState(phase: String, state: State): Nothing = {
    val ex = new IllegalStateException(s"Found illegal state $state in phase $phase")
    handleError(phase, ex)
    throw ex
  }
}

private object Http1ClientStage {

  sealed trait State
  sealed trait ClosedState extends State

  case object Unconnected extends ClosedState // similar to closed, but can transition to idle
  case class Running(var writeChannelClear: Boolean, var readChannelClear: Boolean) extends State
  case class Closed(reason: Throwable) extends ClosedState

  def bufferAsString(buffer: ByteBuffer): String = {
    val b = buffer.duplicate()
    StandardCharsets.UTF_8.decode(b).toString
  }
}
