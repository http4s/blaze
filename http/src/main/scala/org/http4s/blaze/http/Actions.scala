package org.http4s.blaze.http

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

/** Helper functions for the client */
trait Actions { self: HttpClient =>

  /** Perform a GET request
    *
    * @param url request URL
    * @param headers headers to attach to the request
    * @param timeout quiet timeout duration
    * @param action continuation with which to handle the request
    * @param ec `ExecutionContext` on which to run the request
    */
  def GET[A](url: String, headers: Seq[(String, String)] = Nil, timeout: Duration = Duration.Inf)
            (action: ClientResponse => Future[A])
            (implicit ec: ExecutionContext = Execution.trampoline): Future[A] = {
    runReq("GET", url, headers, BufferTools.emptyBuffer, timeout)(action)
  }
}
