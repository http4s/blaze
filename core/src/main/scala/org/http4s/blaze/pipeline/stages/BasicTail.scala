package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.TailStage

final class BasicTail[T](val name: String) extends TailStage[T]
