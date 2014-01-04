package pipeline

import scala.reflect.ClassTag
import scala.concurrent.Future
import pipeline.Command.Command

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
abstract class CapStage[T:ClassTag](name: String,
              private[pipeline] var _prev: Stage[_, T])
              extends Stage[T, Nothing](name)(implicitly[ClassTag[T]], null) {

  def prev(s: Stage[_, T]): Unit = _prev = s
  def prev: Stage[_, T] = _prev

  def next: Stage[Nothing, _] = nextError
  def next(s: Stage[Nothing, _]): Unit = nextError

  private def nextError: Nothing = {
    val msg = s"CapStage ${getClass.getName} doesn't have a next stage"
    logger.error(msg)
    sys.error(msg)
  }

  def replaceNext(stage: Stage[Nothing, _]): stage.type = {
    val msg = s"Stage of type CapStage doesn't have a next stage"
    logger.error(msg)
    sys.error(msg)
  }

  override def inboundCommand(cmd: Command): Unit = defaultActions(cmd)

  override def outboundCommand(cmd: Command): Unit = {
    val msg = "CapStage doesn't receive commands: " + cmd
    logger.error(msg)
    sys.error(msg)
  }

  def replaceInline(stage: Stage[T, Nothing]): stage.type = {
    prev.replaceNext(stage)
    stage
  }

  def handleOutbound(data: Nothing): Future[Unit] = {
    val msg = "CapStage has no reason to ever receive an outbound message"
    logger.error(msg)
    sys.error(msg)
  }

  override protected def untypedOutbound(data: AnyRef): Future[Unit] = {
    val msg = "CapStage shouldn't receive messages: " + data
    logger.error(msg)
    sys.error(msg)
  }

  override protected def untypedInbound(data: AnyRef): Future[Unit] = {
    if (iclass.isAssignableFrom(data.getClass))
      handleInbound(data.asInstanceOf[T])
    else {
      logger.warn(s"CapStage ${getClass.getName} is dropping message $data")
      Future.successful()
    }
  }

  override def findForwardStage(name: String): Option[Stage[_, _]] = {
    if (name == this.name) Some(this) else None
  }

  override def findForwardStage(clazz: Class[_]): Option[Stage[_, _]] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this) else None
  }

  override def findStageHandling[A: ClassTag]: Option[Stage[A, _]] = {
    val clazz = implicitly[ClassTag[A]].runtimeClass

    if (clazz.isAssignableFrom(iclass)) Some(this.asInstanceOf[Stage[A,_]])
    else None
  }
}
