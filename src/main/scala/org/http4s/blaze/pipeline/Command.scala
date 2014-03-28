package org.http4s.blaze
package pipeline

import scala.util.control.NoStackTrace

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
object Command {

  trait Command

  case object Connect extends Command

  case object Disconnect extends Command

  case object Flush extends Command

  case object EOF extends Exception("EOF") with Command with NoStackTrace {
    override def toString() = getMessage
  }

  case class Error(e: Throwable) extends Exception(e) with Command
}
