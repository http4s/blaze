package org.http4s.blaze
package pipeline
package stages

import java.util.HashMap

import pipeline.Command._
import scala.concurrent.{ ExecutionContext, Promise, Future }

import org.http4s.blaze.pipeline.{LeafBuilder, TailStage, HeadStage}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Promise, Future}
import org.http4s.blaze.pipeline.Command._
import org.log4s.getLogger
import java.nio.channels.NotYetConnectedException

import org.http4s.blaze.util.Actors


abstract class HubStage[I, O, K](nodeBuilder: () => LeafBuilder[O], ec: ExecutionContext) extends TailStage[I] {
  private[this] val logger = getLogger
  def name: String = "HubStage"

  trait Node {
    /** Identifier of this node */
    def key: K

    /** Send a message to this node */
    def sendMsg(msg: O): Unit

    /** Shuts down the [[Node]]
      * This [[Node]] is sent the [[Disconnected]] [[InboundCommand]],
      * any pending read requests are sent [[EOF]], and removes it from the [[HubStage]] */
    def shutdown(): Unit
  }

  /** called when a node requests a write operation */
  protected def onNodeWrite(node: Node, data: Seq[O]): Future[Unit]

  /** Will be called when a node needs more data */
  protected def onNodeReadRequest(node: Node, size: Int): Unit

  protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit

  ////////////////////////////////////////////////////////////////////////////////////

  private implicit def _ec = ec
  private val nodeMap = new HashMap[K, NodeHead]()

  // the signal types for our internal actor
  private sealed trait HubMsg
  private case class InboundCmd(cmd: InboundCommand) extends HubMsg
  private case class NodeReadReq(node: NodeHead, p: Promise[O], size: Int) extends HubMsg
  private case class NodeWriteRequest(p: Promise[Unit], node: Node, vs: Seq[O]) extends HubMsg
  private case class NodeOutboundCommand(node: NodeHead, cmd: OutboundCommand) extends HubMsg
  private case object CloseNodes extends HubMsg



  // The actor acts as a thread safe traffic director. All commands should route through it
  private val hubActor = Actors.make[HubMsg] {
    case NodeReadReq(node, p, sz)       => node.onReadRequest(p, sz)
    case InboundCmd(cmd)                => onInboundCommand(cmd)
    case NodeWriteRequest(p, k, v)      => p.completeWith(onNodeWrite(k, v))
    case NodeOutboundCommand(node, cmd) => onNodeCommand(node, cmd)
    case CloseNodes                     => onCloseAllNodes()
  }

  final override def inboundCommand(cmd: InboundCommand): Unit = hubActor ! InboundCmd(cmd)

  /** Called by the [[HubStage]] actor upon inbound command */
  protected def onInboundCommand(cmd: InboundCommand): Unit = cmd match {
    case Connected => stageStartup()
    case Disconnected => stageShutdown()
    case _ =>
      val values = nodeMap.values().iterator()
      while(values.hasNext) {
        values.next().sendInboundCommand(cmd)
      }
  }

  /** Make a new node and connect it to the hub
    * @param key key which identifies this node
    * @return the newly created node
    */
  protected def makeNode(key: K): Node = {
    val node = new NodeHead(key)
    nodeBuilder().base(node)
    val old = nodeMap.put(key, node)
    if (old != null) {
      logger.warn(s"New Node $old with key $key created which replaced an existing Node")
      old.inboundCommand(Disconnected)
    }
    node.stageStartup()
    node
  }

  /** Get a child [[Node]]
    * @param key K specifying the [[Node]] of interest
    * @return `Option[Node]`
    */
  final protected def getNode(key: K): Option[NodeHead] = Option(nodeMap.get(key))

  /** Sends the specified node a message
    * @param key K specifying the [[Node]] to contact
    * @param msg message for the [[Node]]
    * @return if the message was successfully sent to the [[Node]]
    */
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

  /** Send the specified [[InboundCommand]] to the [[Node]] with the designated key
    * @param key K specifying the node
    * @param cmd [[InboundCommand]] to send
    */
  final protected def sendNodeCommand(key: K, cmd: InboundCommand): Unit = {
    val node = nodeMap.get(key)
    if (node != null) node.sendInboundCommand(cmd)
    else logger.warn(s"Sent command $cmd to non-existent node with key $key")
  }

  /** Closes all the nodes of this hub stage */
  protected def closeAllNodes(): Unit = hubActor ! CloseNodes

  /** Remove the specified [[Node]] from this [[HubStage]] */
  final protected def removeNode(node: Node): Unit = removeNode(node.key)

  /** Remove the [[Node]] from this [[HubStage]]
    * This method should only be called from 
    * @param key K specifying the [[Node]]
    */
  protected def removeNode(key: K): Unit = {
    val node = nodeMap.remove(key)
    if (node != null) {
      node.stageShutdown()
      node.sendInboundCommand(Disconnected)
    }
    else logger.warn(s"Tried to remove non-existent node with key $key")
  }

  override protected def stageShutdown(): Unit = {
    closeAllNodes()
    super.stageShutdown()
  }

  // Should only be called from the actor
  private def onCloseAllNodes(): Unit = {
    val values = nodeMap.values().iterator()
    while (values.hasNext) {
      val node = values.next()
      node.stageShutdown()
      node.sendInboundCommand(Disconnected)
    }
    nodeMap.clear()
  }

  private[HubStage] class NodeHead(val key: K) extends HeadStage[O] with Node {

    private val inboundQueue = new java.util.LinkedList[O]()
    private var readReq: Promise[O] = null
    @volatile private var connected = false
    @volatile private var initialized = false

    def shutdown(): Unit = removeNode(key)

    /** Send a message to this node */
    def sendMsg(msg: O): Unit = {
      if (readReq != null) {
        val r = readReq
        readReq = null
        r.success(msg)
      }
      else inboundQueue.offer(msg)
    }

    override def writeRequest(data: O): Future[Unit] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[O]): Future[Unit] = {
      if (connected) {
        val p = Promise[Unit]
        hubActor ! NodeWriteRequest(p, this, data)
        p.future
      }
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting write request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }

    override def readRequest(size: Int): Future[O] = {
      if (connected) {
        val p = Promise[O]
        hubActor ! NodeReadReq(this, p, size)
        p.future
      } else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting read request")
        Future.failed(new NotYetConnectedException)
      } else Future.failed(EOF)
    }

    /** Does the read operation for this node
      * @param p Promise to be fulfilled
      * @param size requested read size
      */
    private[HubStage] def onReadRequest(p: Promise[O], size: Int): Unit = {
      if (connected)  {
        val msg = inboundQueue.poll()
        if (msg != null) p.trySuccess(msg)
        else if (readReq != null) p.tryFailure(new Exception(s"Read already pending: $readReq"))
        else {  // No messages in queue, store the promise and notify of read demand
          readReq = p
          onNodeReadRequest(this, size)
        }
      } else if (!initialized) {
        logger.error(s"Uninitialized node with key $key attempting read request")
        p.tryFailure(new NotYetConnectedException)
      } else p.tryFailure(EOF)
    }

    override def outboundCommand(cmd: OutboundCommand): Unit = hubActor ! NodeOutboundCommand(this, cmd)

    override def stageStartup(): Unit = {
      connected = true
      initialized = true
      sendInboundCommand(Connected)
    }

    override def stageShutdown(): Unit = {
      connected = false
      super.stageShutdown()
      if (readReq != null) {
        val r = readReq
        readReq = null
        r.failure(EOF)
      }
    }

    def name: String = "HubStage Hub Head"
  }
}
