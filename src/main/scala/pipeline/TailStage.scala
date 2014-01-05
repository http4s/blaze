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
//trait TailStage[T] extends Stage[T, Nothing] {
//
//  protected final def oclass: Class[Nothing] = ???
//
//  private[pipeline] var prev: Stage[_, T] = null
//
//  final private[pipeline] override def next: Stage[Nothing, _] = {
//    sys.error(s"CapStage ${getClass.getName} doesn't have a next stage")
//  }
//
//  final private[pipeline] override def next_=(stage: Stage[Nothing, _]) {
//    sys.error(s"CapStage ${getClass.getName} doesn't have a next stage")
//  }
//
//  final override def sendInbound(data: Nothing): Future[Unit] = {
//    sys.error(s"CapStage ${getClass.getName} doesn't have a next stage")
//  }
//
//  final def replaceNext(stage: Stage[Nothing, _]): stage.type = {
//    sys.error(s"Stage of type CapStage doesn't have a next stage")
//  }
//
//  override def inboundCommand(cmd: Command): Unit = defaultActions(cmd)
//
//  final override def outboundCommand(cmd: Command): Unit = {
//    sys.error("CapStage doesn't receive commands: " + cmd)
//  }
//
//  override def replaceInline(stage: Stage[T, Nothing]): stage.type = {
//    cleanup()
//    prev.next = stage
//    stage
//  }
//
//  final def handleOutbound(data: Nothing): Future[Unit] = {
//    sys.error("CapStage has no reason to ever receive an outbound message")
//  }
//
//  final override protected def untypedOutbound(data: AnyRef): Future[Unit] = {
//    sys.error("CapStage shouldn't receive messages: " + data)
//  }
//
//  final override protected def untypedInbound(data: AnyRef): Future[Unit] = {
//    if (iclass.isAssignableFrom(data.getClass))
//      handleInbound(data.asInstanceOf[T])
//    else {
//      logger.warn(s"CapStage ${getClass.getName} is dropping message $data")
//      Future.successful()
//    }
//  }
//
//  override def findForwardStage(name: String): Option[Stage[_, _]] = {
//    if (name == this.name) Some(this) else None
//  }
//
//  override def findForwardStage(clazz: Class[_]): Option[Stage[_, _]] = {
//    if (clazz.isAssignableFrom(this.getClass)) Some(this) else None
//  }
//
//  override def findStageHandling[A: ClassTag]: Option[Stage[A, _]] = {
//    val clazz = implicitly[ClassTag[A]].runtimeClass
//
//    if (clazz.isAssignableFrom(iclass)) Some(this.asInstanceOf[Stage[A,_]])
//    else None
//  }
//}
