package org.http4s.blaze.pipeline
package stages

import scala.concurrent.{Future, Promise}

final class SlowHead[O] extends HeadStage[O] {

  override def name: String = "SlowHead"

  case class Write(value: O, completion: Promise[Unit])

  private[this] var pendingWrite: Option[Write] = None
  private[this] var pendingRead: Option[Promise[O]] = None

  def takeWrite: Write = synchronized {
    pendingWrite match {
      case Some(write) =>
        pendingWrite = None
        write
      case None =>
        throw new IllegalStateException("Write doesn't exist!")
    }
  }

  def takeRead: Promise[O] = synchronized {
    pendingRead match {
      case Some(p) =>
        pendingRead = None
        p

      case None =>
        throw new IllegalStateException("Read doesn't exist!")
    }
  }

  def readRequest(size: Int): Future[O] = synchronized {
    if (pendingRead.isDefined) Future.failed(
      new IllegalStateException("Read guard breached!"))
    else {
      val p = Promise[O]
      pendingRead = Some(p)
      p.future
    }
  }

  def writeRequest(data: O): Future[Unit] = synchronized {
    if (pendingWrite.isDefined) Future.failed(
      new IllegalStateException("Write guard breached!"))
    else {
      val p = Promise[Unit]
      pendingWrite = Some(Write(data, p))
      p.future
    }
  }
}
