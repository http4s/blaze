package blaze.pipeline.stages

import blaze.pipeline.{TailStage, HeadStage, RootBuilder, BaseStage}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Promise, Future}
import blaze.pipeline.Command._
import java.nio.channels.NotYetConnectedException

/**
 * @author Bryce Anderson
 *         Created on 1/25/14
 */

abstract class HubStage[I, O, K](nodeBuilder: RootBuilder[O, O] => HeadStage[O]) extends TailStage[I] {

  def name: String = "HubStage"

  /** methods that change the contents of the nodes map or operate on all elements of the map
    * synchronize on it, to avoid situations where the elements of the map are changed while
    * something is iterating over its members
    */
  private val nodeMap = new ConcurrentHashMap[K, NodeHead]()

  protected def nodeReadRequest(key: K, size: Int): Unit

  protected def onNodeWrite(key: K, data: O): Future[Any]

  protected def onNodeWrite(key: K, data: Seq[O]): Future[Any]

  protected def onNodeCommand(key: K, cmd: Command): Unit

  protected def newHead(key: K): NodeHead = new NodeHead(key)

  override def inboundCommand(cmd: Command): Unit = cmd match{
    case Connected => stageStartup()
    case Shutdown => stageShutdown()
    case _ => nodeMap.synchronized {
      val keys = nodeMap.keys()
      while(keys.hasMoreElements) sendNodeCommand(keys.nextElement(), cmd)
    }

  }

  /** Make a new node and connect it to the hub
    * @param key key which identifies this node
    * @return the newly created node
    */
  protected def makeNode(key: K): NodeHead = nodeMap.synchronized {
    val hub = newHead(key)
    nodeBuilder(new RootBuilder(hub, hub))
    val old = nodeMap.put(key, hub)

    if (old != null) {
      logger.warn(s"New Node $old with key $key created which replaced an existing Node")
      old.inboundCommand(Shutdown)
    }

    hub
  }

  final protected def makeAndInitNode(key: K): NodeHead = {
    val node = makeNode(key)
    node.inboundCommand(Connected)
    node
  }

  final protected def sendNodeMessage(key: K, msg: O): Boolean = {
    getNode(key) match {
      case Some(node) =>
        node.sendMsg(msg)
        true

      case None =>
        logger.warn(s"Attempted to send message $msg to non-existent node with key $key")
        false
    }
  }

  final protected def sendNodeCommand(key: K, cmd: Command) {
    val hub = nodeMap.get(key)
    if (hub != null) hub.inboundCommand(cmd)
    else logger.warn(s"Sent command $cmd to non-existent node with key $key")
  }
  
  protected def removeNode(key: K): Unit = nodeMap.synchronized {
    val node = nodeMap.remove(key)
    if (node != null) node.inboundCommand(Shutdown)
    else logger.warn(s"Tried to remove non-existent node with key $key")
  }

  protected final def closeAllNodes(): Unit = nodeMap.synchronized {
    val keys = nodeMap.keys()
    while (keys.hasMoreElements) removeNode(keys.nextElement())
  }

  final protected def getNode(key: K): Option[NodeHead] = Option(nodeMap.get(key))

  class NodeHead(val key: K) extends HeadStage[O] {

    private val inboundQueue = new java.util.LinkedList[O]()
    private var readReq: Promise[O] = null

    @volatile private var connected = false
    @volatile private var initialized = false

    def sendMsg(msg: O): Unit = inboundQueue.synchronized {
      if (readReq != null) {
        val r = readReq
        readReq = null
        r.success(msg)
      }
      else inboundQueue.offer(msg)
    }

    def readRequest(size: Int): Future[O] = {
      if (connected) inboundQueue.synchronized {
        val msg = inboundQueue.poll()
        if (msg != null) Future.successful(msg)
        else if (readReq != null) Future.failed(new Exception(s"Read already pending: $readReq"))
        else {  // No messages in queue
          readReq = Promise[O]
          nodeReadRequest(key, size)
          readReq.future
        }
      }
      else if (!initialized) {
        logger.error(s"Uninitialized node with key $key attempting read request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }

    def writeRequest(data: O): Future[Any] = {
      if (connected) onNodeWrite(key, data)
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting write request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }

    override def writeRequest(data: Seq[O]): Future[Any] = {
      if (connected) onNodeWrite(key, data)
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting write request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }

    override def outboundCommand(cmd: Command): Unit = onNodeCommand(key, cmd)

    override protected def stageStartup(): Unit = {
      connected = true
      initialized = true
      super.stageStartup()
    }

    override protected def stageShutdown(): Unit = {
      connected = false
      super.stageShutdown()
      inboundQueue.synchronized {
        if (readReq != null) {
          val r = readReq
          readReq = null
          r.failure(EOF)
        }
      }
    }

    def name: String = "HubStage Hub Head"
  }
}
