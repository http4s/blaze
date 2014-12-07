package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.HeadStage
import scala.concurrent.Future
import org.http4s.blaze.pipeline.Command.EOF

class SeqHead[O](private var data: Seq[O]) extends HeadStage[O] {

  @volatile var results: Vector[O] = Vector.empty

  private val lock = new AnyRef

  def name: String = "SeqHead test HeadStage"

  def readRequest(size: Int): Future[O] = lock.synchronized {
    if (!data.isEmpty) {
      val h = data.head
      data = data.tail
      Future.successful(h)
    }
    else Future.failed(EOF)
  }

  def writeRequest(data: O): Future[Unit] = lock.synchronized {
    results :+= data
    Future.successful(())
  }

}
