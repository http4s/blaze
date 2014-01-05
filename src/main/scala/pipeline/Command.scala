package pipeline

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
object Command {
  sealed trait Command

  case object Connected extends Command
  case object Shutdown extends Command
  case object Removed extends Command
  case class ReadRequest(bytes: Int) extends Command
  object ReadRequest extends ReadRequest(-1)

  case class Error(e: Throwable) extends Command
}
