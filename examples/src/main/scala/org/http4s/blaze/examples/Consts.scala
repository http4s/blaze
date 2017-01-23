package org.http4s.blaze.examples

object Consts {

  /** Default thread pool size. */
  val poolSize = (Runtime.getRuntime.availableProcessors()*1.5 + 1).toInt

}
