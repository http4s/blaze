package pipeline

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
object Command {
  sealed trait Command

  object Startup extends Command
  object Shutdown extends Command
  case class Error(e: Throwable) extends Command
}
