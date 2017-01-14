package org.http4s.blaze.servertestsuite

import scala.collection.JavaConverters._

import org.asynchttpclient.{Request, RequestBuilder}
import org.scalacheck.Gen

object GeneralInstances {
  
  private val tokenChars: Seq[Char] = Vector('!', '#', '$', '%', '&', ''', '*' , '+' , '-', '.', '^', '_', '`', '|', '~') ++
    ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')

  private val genToken: Gen[String] =
  for {
    size  <- Gen.chooseNum(1, tokenChars.length)
    chars <- Gen.pick(size, tokenChars)
  } yield chars.mkString

  private val genMaybeQuotedToken: Gen[String] =
    for {
      quoted <- Gen.oneOf(true, false)
      token <- genToken
    } yield if (quoted) ("\"" + token + "\"") else token

  // includes the leading "/", if its necessary
  private val genUrl: Gen[String] = {
    val longUrl =
      for {
        i <- Gen.chooseNum(1, 20)
        segs <- Gen.listOfN(i, genToken)
      } yield segs.mkString("/", "/", "")

    val empty = Gen.const("/")
    val slash = Gen.const("")
    Gen.frequency(1 -> slash, 1 -> empty, 1 -> longUrl)
  }

  // TODO: there are more structures of valid headers
  private lazy val genHeaderKey: Gen[String] = genMaybeQuotedToken
  
  private lazy val genHeader: Gen[(String, List[String])] =
    for {
      k    <- genToken
      vals <- Gen.chooseNum(1, 10)
      v    <- Gen.listOfN(vals, genHeaderKey)
    } yield k -> v

  val genHeaders: Gen[List[(String, List[String])]] = Gen.listOf(genHeader)

  // TODO: this could be more interesting: what about chunked?
  val genBody: Gen[String] = Gen.alphaStr

  val genMethod: Gen[String] = Gen.oneOf("GET", "POST", "PUT")

  // What about cookies? Maybe those are just special headers? Same goes for the query params...
  def genRequest(host: String, port: Int): Gen[Request] =
    for {
      method <- genMethod
      url    <- genUrl
      hs     <- genHeaders
      body   <- genBody
    } yield {
      val b = new RequestBuilder()
      b.setMethod(method)
      b.setUrl(s"http://$host:$port$url")
      hs.foreach { case (k, v) => b.addHeader(k, v.asJava) }
      if (method != "GET") b.setBody(body)
      b.build()
    }

}
