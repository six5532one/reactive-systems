/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor with ActorLogging {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case Insert(requester: ActorRef, id: Int, elem: Int) => root ! Insert(requester, id, elem)
    case Contains(requester: ActorRef, id: Int, elem: Int) => root ! Contains(requester, id, elem)
    case Remove(requester: ActorRef, id: Int, elem: Int) => root ! Remove(requester, id, elem)
    case GC =>
      val newRoot = createRoot
      context.become(garbageCollecting(newRoot))
      root ! CopyTo(newRoot)
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case op: Operation => pendingQueue = pendingQueue.enqueue(op)
    case CopyFinished =>
      log.debug("received CopyFinished")
      val withGarbage = root
      root = newRoot
      // Whenever an actor is stopped, all of its children are recursively stopped too.
      // https://doc.akka.io/docs/akka/current/guide/tutorial_1.html#the-actor-lifecycle
      withGarbage ! PoisonPill
      pendingQueue.foreach(op => root ! op)
      pendingQueue = Queue.empty[Operation]
      context.become(receive)
  }
}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor with ActorLogging {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  val InsertThisNodeDuringCopyId = -1

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case msg @ Insert(requester: ActorRef, id: Int, elemToInsert: Int) =>
      log.debug("Received {} from {}", msg, sender())
      if (elem == elemToInsert) {
        removed = false
        requester ! OperationFinished(id)
      }
      else {
        val direction = if (elemToInsert < elem) Left else Right
        subtrees.get(direction) match {
          case Some(subtreeRoot) => subtreeRoot ! Insert(requester, id, elemToInsert)
          case None =>
            val subtreeRoot = context.actorOf(props(elemToInsert, initiallyRemoved = false), name = s"NodeWithElemEqualTo$elemToInsert")
            subtrees = subtrees + (direction -> subtreeRoot)
            requester ! OperationFinished(id)
        }
      }
    case msg @ Contains(requester: ActorRef, id: Int, elemToFind: Int) =>
      log.debug("Received {}", msg)
      if (elem == elemToFind) requester ! ContainsResult(id, !removed)
      else {
        val direction = if (elemToFind < elem) Left else Right
        subtrees.get(direction) match {
          case Some(subtreeRoot) => subtreeRoot ! Contains(requester, id, elemToFind)
          case None => requester ! ContainsResult(id, false)
        }
      }
    case Remove(requester: ActorRef, id: Int, elemToRemove: Int) =>
      if (elem == elemToRemove) {
        removed = true
        requester ! OperationFinished(id)
      }
      else {
        val direction = if (elemToRemove < elem) Left else Right
        subtrees.get(direction) match {
          case Some(subtreeRoot) => subtreeRoot ! Remove(requester, id, elemToRemove)
          case None => requester ! OperationFinished(id)
        }
      }
    case msg @ CopyTo(treeNode: ActorRef) =>
      log.debug("received {} from sender {}", msg, sender())
      if (!removed)
        treeNode ! Insert(self, InsertThisNodeDuringCopyId, elem)

      (subtrees.get(Left), subtrees.get(Right)) match {
        case (Some(left), Some(right)) =>
          left ! CopyTo(treeNode)
          right ! CopyTo(treeNode)
          context.become(copying(Set(left, right), removed))
        case (Some(left), None) =>
          left ! CopyTo(treeNode)
          context.become(copying(Set(left), removed))
        case (None, Some(right)) =>
          right ! CopyTo(treeNode)
          context.become(copying(Set(right), removed))
        case (None, None) => context.become(copying(Set(), removed))
      }
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case CopyFinished =>
      log.debug("Received CopyFinished {} from sender {}", sender())
      val remainingExpected = expected - sender()
      if (remainingExpected.isEmpty && insertConfirmed) {
        context.parent ! CopyFinished
        context.become(receive)
      }
      else context.become(copying(remainingExpected, insertConfirmed))
    case msg @ OperationFinished(InsertThisNodeDuringCopyId) =>
      log.debug("Received {} from sender {}", msg, sender())
      if (expected.isEmpty) {
        context.parent ! CopyFinished
        context.become(receive)
      }
      else context.become(copying(expected, true))
  }
}
