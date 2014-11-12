package org.http4s.blaze
package pipeline
package stages

import java.util.concurrent.ConcurrentHashMap

import pipeline.Command._
import scala.concurrent.{ ExecutionContext, Future }

import org.http4s.blaze.pipeline.{LeafBuilder, TailStage, HeadStage}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Promise, Future}
import org.http4s.blaze.pipeline.Command._
import org.log4s.getLogger
import java.nio.channels.NotYetConnectedException


abstract class HubStage[I, O, K](nodeBuilder: () => LeafBuilder[O], ec: ExecutionContext) extends TailStage[I] {

  def name: String = "HubStage"

  sealed trait Node {
    /** Identifier of this node */
    def key: K

    /** Shuts down the [[Node]]
      * This [[Node]] is sent the [[Disconnected]] [[InboundCommand]],
      * any pending read requests are sent [[EOF]], and removes it from the [[HubStage]] */
    def shutdown(): Unit
  }

  /** called when a node requests a write operation */
  protected def onNodeWrite(node: Node, data: Seq[O]): Future[Unit]

  /** called when a node needs more data */
  protected def onNodeRead(node: Node, size: Int): Future[O]

  /** called when a node sends an outbound command */
  protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit

  ////////////////////////////////////////////////////////////////////////////////////

  private implicit def _ec = ec
  private val nodeMap = new ConcurrentHashMap[K, NodeHead]()

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
  protected def closeAllNodes(): Unit = {
    val values = nodeMap.values().iterator()
    while (values.hasNext) {
      val node = values.next()
      node.stageShutdown()
      node.sendInboundCommand(Disconnected)
    }
    nodeMap.clear()
  }

  /** Remove the specified [[Node]] from this [[HubStage]] */
  final protected def removeNode(node: Node): Unit = removeNode(node.key)

  /** Remove the [[Node]] from this [[HubStage]]
    * This method should only be called from 
    * @param key K specifying the [[Node]]
    */
  protected def removeNode(key: K): Option[Node] = {
    val node = nodeMap.remove(key)
    if (node != null) {
      node.stageShutdown()
      node.sendInboundCommand(Disconnected)
      Some(node)
    }
    else None
  }

  override protected def stageShutdown(): Unit = {
    closeAllNodes()
    super.stageShutdown()
  }


  private[HubStage] class NodeHead(val key: K) extends HeadStage[O] with Node {

    def name: String = "HubStage Hub Head"
    private var connected = false
    private var initialized = false

    def shutdown(): Unit = removeNode(key)

    override def writeRequest(data: O): Future[Unit] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[O]): Future[Unit] = {
      if (connected) onNodeWrite(this, data)
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting write request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }

    override def readRequest(size: Int): Future[O] =  {
      if (connected) onNodeRead(this, size)
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting read request")
        Future.failed(new NotYetConnectedException)
      } else Future.failed(EOF)
    }

    override def outboundCommand(cmd: OutboundCommand): Unit =
      onNodeCommand(this, cmd)

    override def stageStartup(): Unit = {
      connected = true
      initialized = true
      sendInboundCommand(Connected)
    }

    override def stageShutdown(): Unit = {
      connected = false
      super.stageShutdown()
    }
  }
}
