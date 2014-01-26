package blaze
package pipeline

import scala.util.control.NoStackTrace

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
object Command {
  trait Command

  case object Connected extends Command

  case object Shutdown extends Command

  case object Flush extends Command

  case object EOF extends Exception with Command with NoStackTrace {
    override def toString() = "EOF"
  }

  case class Error(e: Throwable) extends Command
}
