package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.http4s.blaze.http.parser.BaseExceptions.BadCharacter
import org.http4s.blaze.http.{BodyReader, HttpClientConfig, HttpClientSession, HttpRequest}
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, ReadPool}
import org.specs2.mutable.Specification
import scala.concurrent.{Await, Awaitable, Future, Promise}
import scala.concurrent.duration._

class Http1ClientStageSpec extends Specification {
  private val config = HttpClientConfig.Default

  case class Write(data: ByteBuffer, p: Promise[Unit])

  private class TestHead extends HeadStage[ByteBuffer] {

    private val readP = new ReadPool[ByteBuffer]
    private var writes: Option[Write] = None

    def pendingWrite(): Option[Write] = {
      val r = writes
      writes = None
      r
    }

    def close(): Unit = readP.close()

    override protected def doClosePipeline(cause: Option[Throwable]): Unit = {
      assert(cause == None)
      close()
    }

    def consumeWrite(): Option[String] = pendingWrite().map {
      case Write(d, p) =>
        p.trySuccess(())
        StandardCharsets.US_ASCII.decode(d).toString
    }

    def offerInbound(b: ByteBuffer): Unit = {
      readP.offer(b)
      ()
    }

    def offerInbound(s: String): Unit =
      offerInbound(StandardCharsets.US_ASCII.encode(s))

    override def readRequest(size: Int): Future[ByteBuffer] = readP.read()

    override def writeRequest(data: collection.Seq[ByteBuffer]): Future[Unit] =
      writeRequest(BufferTools.joinBuffers(data))

    override def writeRequest(data: ByteBuffer): Future[Unit] = {
      writes match {
        case Some(_) => Future.failed(new IllegalStateException())
        case None =>
          val p = Promise[Unit]
          writes = Some(Write(data,p))
          p.future
      }
    }

    override def name: String = "TestHead"
  }

  private def toBodyReader(s: String): BodyReader =
    BodyReader.singleBuffer(StandardCharsets.UTF_8.encode(s))

  private def buildPair(config: HttpClientConfig = config): (TestHead, Http1ClientStage) = {
    val tail = new Http1ClientStage(config)
    val head = new TestHead
    LeafBuilder(tail).base(head)
    head.sendInboundCommand(Command.Connected)
    (head, tail)
  }

  private def await[T](t: Awaitable[T]): T =
    Await.result(t, 5.seconds)

  "Http1ClientStage prelude encoding" should {
    "should start life with status Busy and transition to Ready on connect" in {
      val tail = new Http1ClientStage(config)
      tail.status must_== HttpClientSession.Busy
      val head = new TestHead
      LeafBuilder(tail).base(head)
      head.sendInboundCommand(Command.Connected)
      tail.status must_== HttpClientSession.Ready
    }

    def checkPrelude(request: HttpRequest): String = {
      val (head, tail) = buildPair()
      tail.dispatch(request)
      head.consumeWrite().getOrElse(sys.error("No pending write"))
    }

    "Send a prelude on a simple request HTTP/1.1 request" in {
      val request = HttpRequest("GET", "/home", 1, 1, Seq("Host" -> ""), BodyReader.EmptyBodyReader)
      checkPrelude(request) must_== "GET /home HTTP/1.1\r\nHost:\r\n\r\n"
    }

    "Send a prelude on a simple request HTTP/1.0 request" in {
      checkPrelude(HttpRequest("GET", "/home", 1, 0, Seq(), BodyReader.EmptyBodyReader)
      ) must_== "GET /home HTTP/1.0\r\n\r\n"

      checkPrelude(HttpRequest("GET", "http://foo.com/home", 1, 0, Seq(), BodyReader.EmptyBodyReader)
      ) must_== "GET /home HTTP/1.0\r\n\r\n"
    }

    "Send a prelude on a simple HTTP/1.1 request without a host header" in {
      val request = HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader)
      checkPrelude(request) must_== "GET /home HTTP/1.1\r\nHost:foo.com\r\n\r\n"
    }

    "Send a prelude on a simple HTTP/1.1 request with a body without content-length" in {
      val request = HttpRequest("POST", "http://foo.com/home", 1, 1, Seq(), toBodyReader("cat"))
      checkPrelude(request) must_== "POST /home HTTP/1.1\r\nHost:foo.com\r\nTransfer-Encoding:chunked\r\n\r\n"
    }

    "Send a prelude on a simple HTTP/1.1 request with a body with content-length" in {
      val request = HttpRequest(
        "POST", "http://foo.com/home", 1, 1, Seq("Content-Length" -> "3"), toBodyReader("cat"))
      checkPrelude(request) must_== "POST /home HTTP/1.1\r\nContent-Length:3\r\nHost:foo.com\r\n\r\n"
    }

    "Send a prelude on a simple HTTP/1.1 request with a body with Transfer-Encoding" in {
      val request = HttpRequest(
        "POST", "http://foo.com/home", 1, 1, Seq("Transfer-Encoding" -> "chunked"), toBodyReader("cat"))
      checkPrelude(request) must_== "POST /home HTTP/1.1\r\nTransfer-Encoding:chunked\r\nHost:foo.com\r\n\r\n"
    }
  }

  "http1ClientStage request encoding" should {
    "encodes a request with content-length" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest(
        "POST",
        "http://foo.com/home",
        1, 1,
        Seq("Content-Length" -> "3"), toBodyReader("foo")))
      head.consumeWrite() must beSome(
        "POST /home HTTP/1.1\r\n" +
          "Content-Length:3\r\n" +
          "Host:foo.com\r\n\r\n"
      )

      tail.status must_== HttpClientSession.Busy
      head.consumeWrite() must beSome("foo")
      tail.status must_== HttpClientSession.Busy // Haven't sent a response
      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Content-Length:0\r\n\r\n")

      await(f)
      tail.status must_== HttpClientSession.Ready
    }

    "encodes a request with transfer-encoding" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest(
        "POST",
        "http://foo.com/home",
        1, 1,
        Seq("Transfer-Encoding" -> "chunked"), toBodyReader("foo")))
      head.consumeWrite() must beSome(
        "POST /home HTTP/1.1\r\n" +
          "Transfer-Encoding:chunked\r\n" +
          "Host:foo.com\r\n\r\n"
      )

      tail.status must_== HttpClientSession.Busy
      head.consumeWrite() must beSome("3\r\nfoo")
      head.consumeWrite() must beSome("0\r\n\r\n")
      tail.status must_== HttpClientSession.Busy // Haven't sent a response
      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Content-Length:0\r\n\r\n")

      await(f)
      tail.status must_== HttpClientSession.Ready
    }

    "Adds Transfer-Encoding if request has body but no content-length" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest(
        "POST",
        "http://foo.com/home",
        1, 1,
        Seq(), toBodyReader("foo")))
      head.consumeWrite() must beSome(
        "POST /home HTTP/1.1\r\n" +
          "Host:foo.com\r\n" +
          "Transfer-Encoding:chunked\r\n\r\n"
      )

      tail.status must_== HttpClientSession.Busy
      head.consumeWrite() must beSome("3\r\nfoo")
      head.consumeWrite() must beSome("0\r\n\r\n")
      tail.status must_== HttpClientSession.Busy // Haven't sent a response
      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Content-Length:0\r\n\r\n")

      await(f)
      tail.status must_== HttpClientSession.Ready
    }

    "Discards reader on write prelude failure" in {
      val underlying = toBodyReader("foo")
      object reader extends BodyReader.Proxy(underlying) {
        @volatile
        var discarded = false
        override def discard(): Unit = {
          discarded = true
          super.discard()
        }
      }
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest(
        "POST",
        "http://foo.com/home",
        1, 1,
        Seq(), reader))

      head.pendingWrite().get.p.tryFailure(new Throwable("boom"))
      head.close()
      await(f) must throwA[Command.EOF.type]
      reader.discarded must beTrue
      tail.status must_== HttpClientSession.Closed // Haven't sent a response
    }
  }

  "Discards reader on write body failure" in {
    val underlying = toBodyReader("foo")
    object reader extends BodyReader.Proxy(underlying) {
      @volatile
      var discarded = false
      override def discard(): Unit = {
        discarded = true
        super.discard()
      }
    }
    val (head, tail) = buildPair()
    val f = tail.dispatch(HttpRequest(
      "POST",
      "http://foo.com/home",
      1, 1,
      Seq(), reader))

    // Accept the prelude
    head.consumeWrite() must beSome(
      "POST /home HTTP/1.1\r\n" +
        "Host:foo.com\r\n" +
        "Transfer-Encoding:chunked\r\n\r\n"
    )

    // Fail the body
    head.pendingWrite().get.p.tryFailure(new Throwable("boom"))
    head.close()
    await(f) must throwA[Command.EOF.type]
    reader.discarded must beTrue
    tail.status must_== HttpClientSession.Closed // Haven't sent a response
  }

  "Http1ClientStage response parsing" should {
    "passes prelude parser failures to the response" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r" + // missing a \n after the \r
        "Content-Length: 3\r\n\r\n" +
        "foo")

      await(f) must throwA[BadCharacter]
      tail.status must_== HttpClientSession.Closed
    }

    "passes chunked encoding failures to the BodyReader" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n")
      head.offerInbound(  // split it into a prelude and body to make sure we trampoline through a read
        "3\r" + // missing trailing '\n'
        "foo\r\n" +
        "0\r\n\r\n")

      val r = await(f)
      r.code must_== 200
      r.status must_== "OK"
      r.headers must_== Seq("Transfer-Encoding" -> "chunked")

      await(r.body.accumulate()) must throwA[BadCharacter]
      tail.status must_== HttpClientSession.Closed
    }

    "receive a simple response with body" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Content-Length: 3\r\n\r\n" +
        "foo")

      val r = await(f)
      r.code must_== 200
      r.status must_== "OK"
      r.headers must_== Seq("Content-Length" -> "3")

      StandardCharsets.US_ASCII.decode(await(r.body.accumulate())).toString must_== "foo"
      tail.status must_== HttpClientSession.Ready
    }

    "receive a simple response without body" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Content-Length: 0\r\n\r\n")

      val r = await(f)
      r.code must_== 200
      r.status must_== "OK"
      r.headers must_== Seq("Content-Length" -> "0")

      StandardCharsets.US_ASCII.decode(await(r.body.accumulate())).toString must_== ""
      tail.status must_== HttpClientSession.Ready
    }

    "parses a chunked encoded body" in {
      val (head, tail) = buildPair()
      val f = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n")
      head.offerInbound(
        "3\r\n" +
        "foo\r\n" +
        "0\r\n\r\n")

      val r = await(f)
      r.code must_== 200
      r.status must_== "OK"
      r.headers must_== Seq("Transfer-Encoding" -> "chunked")

      StandardCharsets.US_ASCII.decode(await(r.body.accumulate())).toString must_== "foo"
      tail.status must_== HttpClientSession.Ready
    }

    "parses a HEAD response" in {
      val (head, tail) = buildPair()
      val r1 = tail.dispatch(HttpRequest("HEAD", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n")

      val f1 = await(r1)
      f1.code must_== 200
      f1.status must_== "OK"
      f1.headers must_== Seq("Transfer-Encoding" -> "chunked")

      // No associated body
      tail.status must_== HttpClientSession.Ready // should not need to read the body to be ready
      StandardCharsets.US_ASCII.decode(await(f1.body.accumulate())).toString must_== ""
      tail.status must_== HttpClientSession.Ready

      // Dispatch another request after the HEAD
      val r2 = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n" +
        "3\r\n" +
        "foo\r\n" +
        "0\r\n\r\n")

      val f2 = await(r2)
      f2.code must_== 200
      f2.status must_== "OK"
      f2.headers must_== Seq("Transfer-Encoding" -> "chunked")

      // No associated body
      StandardCharsets.US_ASCII.decode(await(f2.body.accumulate())).toString must_== "foo"
      tail.status must_== HttpClientSession.Ready
    }

    "parses 204 and 304 responses" in {
      forall(Seq(100, 204, 304)) { code =>
        val (head, tail) = buildPair()
        val r1 = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
        head.consumeWrite() must beSome

        head.offerInbound(s"HTTP/1.1 $code STATUS\r\n\r\n")

        val f1 = await(r1)
        f1.code must_== code
        f1.status must_== "STATUS"
        f1.headers must_== Seq()

        // No associated body
        tail.status must_== HttpClientSession.Ready // should not need to read the body to be ready
        StandardCharsets.US_ASCII.decode(await(f1.body.accumulate())).toString must_== ""
        tail.status must_== HttpClientSession.Ready

        // Dispatch another request after the HEAD
        val r2 = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
        head.consumeWrite() must beSome

        head.offerInbound("HTTP/1.1 200 OK\r\n" +
          "Transfer-Encoding: chunked\r\n\r\n" +
          "3\r\n" +
          "foo\r\n" +
          "0\r\n\r\n")

        val f2 = await(r2)
        f2.code must_== 200
        f2.status must_== "OK"
        f2.headers must_== Seq("Transfer-Encoding" -> "chunked")

        // No associated body
        StandardCharsets.US_ASCII.decode(await(f2.body.accumulate())).toString must_== "foo"
        tail.status must_== HttpClientSession.Ready
      }
    }

    "parses an empty response then a chunked encoded response" in {
      val (head, tail) = buildPair()
      val f1 = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome // consume the request

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Content-Length: 0\r\n\r\n")

      val r1 = await(f1)
      r1.code must_== 200
      r1.status must_== "OK"
      r1.headers must_== Seq("Content-Length" -> "0")
      tail.status must_== HttpClientSession.Ready

      // Next dispatch
      val f2 = tail.dispatch(HttpRequest("GET", "http://foo.com/home", 1, 1, Seq(), BodyReader.EmptyBodyReader))
      head.consumeWrite() must beSome // consume the request

      head.offerInbound("HTTP/1.1 200 OK\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n")
      head.offerInbound(
        "3\r\n" +
          "foo\r\n" +
          "0\r\n\r\n")

      val r2 = await(f2)
      r2.code must_== 200
      r2.status must_== "OK"
      r2.headers must_== Seq("Transfer-Encoding" -> "chunked")

      StandardCharsets.US_ASCII.decode(await(r2.body.accumulate())).toString must_== "foo"

      tail.status must_== HttpClientSession.Ready
    }
  }
}
