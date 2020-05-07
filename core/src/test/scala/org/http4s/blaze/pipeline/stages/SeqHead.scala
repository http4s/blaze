package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.util.{FutureEOF, FutureUnit}
import scala.concurrent.Future

class SeqHead[O](private var data: Seq[O]) extends HeadStage[O] {
  private val lock = new AnyRef
  private var acc: Vector[O] = Vector.empty

  override protected def doClosePipeline(cause: Option[Throwable]): Unit = ???

  def results: Seq[O] =
    lock.synchronized {
      acc
    }

  def name: String = "SeqHead test HeadStage"

  def readRequest(size: Int): Future[O] =
    lock.synchronized {
      if (!data.isEmpty) {
        val h = data.head
        data = data.tail
        Future.successful(h)
      } else FutureEOF
    }

  def writeRequest(data: O): Future[Unit] =
    lock.synchronized {
      acc :+= data
      FutureUnit
    }
}
