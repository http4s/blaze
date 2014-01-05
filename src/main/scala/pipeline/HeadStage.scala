//package pipeline
//
//import scala.reflect.ClassTag
//import scala.concurrent.Future
//import pipeline.Command.Command
//
///**
// * @author Bryce Anderson
// *         Created on 1/4/14
// */
//trait HeadStage[T] extends Stage[Nothing, T] {
//
//  private[pipeline] var next: Stage[T, _] = null
//
//
//  protected final def oclass: Class[T] = {
//    sys.error("HeadStage doesn't have an outbound type")
//  }
//
//  override protected def untypedOutbound(data: AnyRef): Future[Unit] = {
//    if (oclass.isAssignableFrom(data.getClass))
//      handleOutbound(data.asInstanceOf[T])
//    else {
//      logger.warn(s"Data $data reached head of the pipeline unhandled. Dropping.")
//      Future.successful()
//    }
//  }
//
//  override def outboundCommand(cmd: Command): Unit = {
//    defaultActions(cmd)
//  }
//
//  final protected def iclass: Class[Nothing] = {
//    sys.error("HeadStage doesn't have an inbound class")
//  }
//
//  final def handleInbound(data: Nothing): Future[Unit] = {
//    sys.error("HeadStage doesn't receive data directly")
//  }
//
//  final override def replaceInline(stage: Stage[Nothing, T]): stage.type = {
//    sys.error("Cannot replace HeadStage")
//  }
//
//  override def prev: Stage[_, Nothing] = {
//    sys.error("HeadStage doesn't have a previous node")
//  }
//
//  override def prev_=(stage: Stage[_, Nothing]) {
//    sys.error("HeadStage doesn't have a previous node")
//  }
//
//  final override def sendOutbound(data: Nothing): Future[Unit] = {
//    sys.error("HeadStage doesn't have a previous node")
//  }
//}
