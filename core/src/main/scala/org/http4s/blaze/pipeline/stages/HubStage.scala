package org.http4s.blaze
package pipeline
package stages


import java.util.HashMap
import java.nio.channels.NotYetConnectedException

import scala.collection.mutable
import scala.concurrent.Future

import org.http4s.blaze.pipeline.Command._



abstract class HubStage[I] extends TailStage[I] {
  
  type Out                  // type of messages accepted by the nodes
  type Key                  // type that will uniquely determine the nodes
  protected type NodeT <: Node with HeadStage[I] // state that can be appended to the node

  /** Construct a new `NodeT` */
  protected def newNode(key: Key): NodeT

  ////////////////////////////////////////////////////////////////////////////////////

  private val nodeMap = new HashMap[Key, NodeT]()

  final protected def nodeCount(): Int = nodeMap.size()

  /** Make a new node and connect it to the hub if the key doesn't already exist
    * @param key key which identifies this node
    * @return the newly created node in an unstarted state. To begin the node
    *         send a [[Connected]] command or call its `startNode()` method
    */
  protected def makeNode(key: Key, builder: LeafBuilder[Out]): Option[NodeT] = {
    if (!nodeMap.containsKey(key)) {
      val node = newNode(key)
      nodeMap.put(key, node)
      builder.base(node)
      Some(node)
    }
    else None
  }

  /** Get a child [[Node]]
    * @param key K specifying the [[Node]] of interest
    * @return `Option[Node]`
    */
  final protected def getNode(key: Key): Option[NodeT] = Option(nodeMap.get(key))

  /** Get an iterator over the nodes attached to this [[HubStage]] */
  final protected def nodes(): Seq[NodeT] =
    mutable.WrappedArray.make(nodeMap.values().toArray())

  /** Closes all the nodes of this hub stage */
  protected def closeAllNodes(): Unit = {
    val values = nodeMap.values().iterator()
    while (values.hasNext) {
      val node = values.next()
      values.remove()
      checkShutdown(node)
    }
  }

  /** Remove the specified [[Node]] from this [[HubStage]] */
  final protected def removeNode(node: Node): Unit = removeNode(node.key)

  /** Remove the [[Node]] from this [[HubStage]]
    * This method should only be called from 
    * @param key K specifying the [[Node]]
    */
  protected def removeNode(key: Key): Option[Node] = {
    val node = nodeMap.remove(key)
    if (node != null) {
      checkShutdown(node)
      Some(node)
    }
    else None
  }

  override protected def stageShutdown(): Unit = {
    closeAllNodes()
    super.stageShutdown()
  }

  ////////////////////////////////////////////////////////////

  private def checkShutdown(node: NodeT): Unit = {
    if (node.isConnected()) node.inboundCommand(Disconnected)
  }

  ////////////////////////////////////////////////////////////

  trait Node extends HeadStage[Out] {

//    private sealed trait NodeState
//    private case object NotInitialized extends NodeState
//    private case object Open extends NodeState
//    private case class CloseStream(t: Throwable) extends NodeState

    def isConnected(): Boolean

    val key: Key

    final def startNode(): Unit = inboundCommand(Connected)

    override def toString: String = s"Node[$key]"

    override def writeRequest(data: Out): Future[Unit] = writeRequest(data::Nil)
  }
}
