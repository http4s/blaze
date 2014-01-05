package pipeline

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
object Command {
  sealed trait Command

  case object Connected extends Command
  case object Shutdown extends Command

  case class Error(e: Throwable) extends Command
}
