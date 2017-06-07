package org.http4s.blaze.http.util

import org.http4s.blaze.pipeline.Command

import java.util.concurrent.TimeoutException

import scala.util.{Failure, Try}


/** Helper to collect errors that we don't care much about */
private[http] object NormalDisconnect {
  
  def unapply(t: Try[Any]): Option[Exception] = t match {
    case Failure(t) => unapply(t)
    case _          => None
  }

  def unapply(t: Throwable): Option[Exception] = t match {
    case t@ Command.EOF      => Some(Command.EOF)
    case t: TimeoutException => Some(t)
    case _                   => None
  }

}
