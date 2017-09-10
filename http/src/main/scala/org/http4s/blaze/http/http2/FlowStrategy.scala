package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.FlowStrategy.Increment

/** The goal of the `FlowStrategy` is to advise when to do windows updates for inbound data */
trait FlowStrategy {
  /** Decide if the session window needs to send a WINDOW_UPDATE frame
    *
    * @note This must not mutate the [[SessionFlowControl]] in any way.
    * @note This verison should only be used in situations where the stream associated
    *       with the data does not exist. For example, it may have already closed and
    *       sent a RST frame.
    *
    * @param session the session [[SessionFlowControl]]
    * @return number of bytes to update the session flow window with.
    */
  def checkSession(session: SessionFlowControl): Int

  /** Decide if the stream and/or the session need a WINDOW_UPDATE frame
    *
    * @note This must not mutate the [[SessionFlowControl]] or the [[StreamFlowWindow]] in any way.
    *
    * @param session the session [[SessionFlowControl]]
    * @param stream the stream [[StreamFlowWindow]]
    * @return the number of bytes to update the session and stream flow window with.
    */
  def checkStream(session: SessionFlowControl, stream: StreamFlowWindow): Increment
}

object FlowStrategy {
  final class Increment private[FlowStrategy](val session: Int, val stream: Int) {
    override def toString: String = s"Increment($session, $stream)"
  }

  val Empty = new Increment(0, 0) // Cached version for avoiding allocations

  object Increment {
    def apply(session: Int, stream: Int): Increment = {
      if (session == 0 && stream == 0) Empty
      else new Increment(session, stream)
    }
  }
}
