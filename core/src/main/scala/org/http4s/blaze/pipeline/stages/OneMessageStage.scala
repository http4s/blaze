package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.MidStage

import scala.concurrent.Future

/** Holds a single element that when read, will eject itself
  * from the pipeline.
  * @note There is an intrinsic race condition between this stage
  *       removing itself and write commands, so this stage should
  *       only be used under half duplex conditions.
  */
class OneMessageStage[T](element: T) extends MidStage[T, T] {

  override def name: String = "OneMessageStage"

  override def readRequest(size: Int): Future[T] = {
    this.removeStage()
    Future.successful(element)
  }

  override def writeRequest(data: T): Future[Unit] =
    channelWrite(data)

  override def writeRequest(data: Seq[T]): Future[Unit] =
    channelWrite(data)
}
