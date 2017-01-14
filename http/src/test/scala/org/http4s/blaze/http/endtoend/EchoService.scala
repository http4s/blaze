package org.http4s.blaze.http.endtoend

import org.http4s.blaze.http._

import scala.concurrent.{ExecutionContext, Future}

object EchoService {

  private implicit def ec = ExecutionContext.global

  def service(request: HttpRequest): Future[ResponseBuilder] = Future.successful(HttpResponse( new RouteAction {
    def handle[Writer <: BodyWriter](commit: (HttpResponsePrelude) => Writer): Future[Writer#Finished] = {
      val w = commit(HttpResponsePrelude(200, "OK", request.headers))

      // Just copy all the data from the input to the output
      def go(): Future[Writer#Finished] = request.body().flatMap { buffer =>
        if (buffer.hasRemaining) w.write(buffer).flatMap(_ => go())
        else w.close()
      }

      go()
    }
  }))

}
