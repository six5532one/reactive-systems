Task
==========
Implement a set using an actor-based binary search tree.


Motivation
===========
An actor-based solution can execute concurrent operations fully asynchronously and in parallel. For example, suppose there are concurrent requests to insert 40 into the tree below and to find out whether it contains -10. Two messages (one for each request) will be sent to the actor that represents the root of the tree. Suppose the actor first queues the message to insert 40, then the message to look up -10. When it processes the message to insert 40, it forwards that message to the actor that represents its right child (the tree node containing 30). Then, it processes the message to look up -10, forwarding that message to the actor that represents its left child (the tree node containing -20). The actor that represents the right child and the actor that represents the left child process their message independently of each other, enabling the two operations on the tree to execute in parallel. Since the clients that requested these operations did not need to wait for a lock and they receive a message with the response, their requests are handled "fully asynchronously".

```
      0
    /   \
  -20   30
  /  \
-50  -10
```


Interface
==========
Each of these operations is represented by an actor message:
* Insert
* Remove
* Contains

Each of the operation messages includes:
* an ActorRef representing the requester of the operation
* a numerical identifier of the operation
* the element to insert/remove/lookup

`Insert` and `Remove` operations should result in an `OperationFinished` message sent to the provided requester ActorRef reference including the id of the operation. `Insert` and `Remove` should return an `OperationFinished` message even if the element was already present in the tree or was not found, respectively. `Contains` should result in a `ContainsResult` message containing the result of the lookup (a Boolean which is true if and only if the element is in the tree when the query arrives) and the identifier of the `Contains` query.


Implementation
==================
Each node in the tree is represented by an actor.

Code stubs were provided in the file BinaryTreeSet.scala, which provided the message API, the BinaryTreeSet and BinaryTreeNode classes. _The BinaryTreeSet represents the whole binary tree. This is also the only actor that is explicitly created by the user and the only actor the user sends messages to._

The course instructors provided suggestions in the form of method signatures in the code stub. These method signatures were marked with the comment "optional".

The course instructors also provided tests in BinaryTreeSuite.scala.


Removal
--------
Removal in a binary tree results in tree restructuring, which means that nodes would need to communicate and coordinate between each other while additional operations arrive from the external world. For the sake of simplicity, this implementation should use a flag ("removed") that is stored in every tree node indicating whether the element in the node has been removed or not. This decision results in the side effect that the tree set accumulates "garbage" (elements that have been removed) over time.


Garbage Collection
-------------------
Because the implementation of the removal operation results in garbage accumulating in the tree, this implementation should include a garbage collection feature. Whenever the binary tree set receives a GC message, it should clean up all the removed elements, while additional operations might arrive from the external world. For the sake of simplicity of this exercise, it is the user who triggers garbage collection by sending a GC message.

The garbage collection task can be implemented in two steps. The first subtask is to implement an internal CopyTo operation on the binary tree that copies all its non-removed contents from the binary tree to a provided new one. This implementation can assume that no operations arrive while the copying happens (i.e. the tree is protected from modifications while copying takes places).

The second part of the implementation is to implement garbage collection in the manager (BinaryTreeSet) by using the copy operation. The newly constructed tree should replace the old one and all actors from the old one should be stopped. Since copying assumes no other concurrent operations, the manager should handle the case when operations arrive while still performing the copy in the background. The fact that garbage collection happens should be invisible from the outside (of course additional delay is allowed). For the sake of simplicity, the implementation should ignore GC requests that arrive while garbage collection is taking place.


Ordering Guarantees
--------------------
Replies to operations may be sent in any order but the contents of ContainsResult replies must obey the order of the operations. To illustrate what this means observe the following example:

Client sends:

Insert(testActor, id=100, elem=1)
Contains(testActor, id=50, elem=2)
Remove(testActor, id=10, elem=1)
Insert(testActor, id=20, elem=2)
Contains(testActor, id=80, elem=1)
Contains(testActor, id=70, elem=2)
Client receives:

ContainsResult(id=70, true)
OperationFinished(id=20)
OperationFinished(id=100)
ContainsResult(id=80, false)
OperationFinished(id=10)
ContainsResult(id=50, false)
While the results seem "garbled", they actually strictly correspond to the order of the original operations. On closer examination you can observe that the order of original operations was [100, 50, 10, 20, 80, 70]. Now if you order the responses according to this sequence the result would be:

Insert(testActor, id=100, elem=1) -> OperationFinished(id=100)
Contains(testActor, id=50, elem=2) -> ContainsResult(id=50, false)
Remove(testActor, id=10, elem=1) -> OperationFinished(id=10)
Insert(testActor, id=20, elem=2) -> OperationFinished(id=20)
Contains(testActor, id=80, elem=1) -> ContainsResult(id=80, false)
Contains(testActor, id=70, elem=2) -> ContainsResult(id=70, true)
As you can see, the responses the client received are the same, hence they must have been executed sequentially, and only the responses have arrived out of order. Thus, the responses obey the semantics of sequential operations -- it is simply their arrival order is not defined. You might find it easier for testing to use sequential identifiers for the operations, since that makes it easier to follow the sequence of responses.

You might also note that out-of-order responses can only happen if the client does not wait for each individual answer before continuing with sending operations.

While this loose ordering guarantee on responses might look strange at first, it will significantly simplify the implementation of the binary tree and you are encouraged to make full use of it.


Outcome
========
The first two tests exercise a sequence of inserts, lookup and removals without issuing any garbage collection commands. These tests pass.

The third test issues a random sequence of 1000 insert, lookup and removal operations. It randomly selects 10% of these operations and issues a garbage collection command after each of them. Since the test probe requests 1000 (non-GC) operations, it expects to receive 1000 `OperationReply` messages. Sometimes this test passes, but usually it fails because it doesn't receive 1000 `OperationReply` messages.

To debug, I logged the messages received by each actor, wrote a driver exercising GC (BTMain.scala), and inspected the logs (actorbintree_BTMain_logs.txt) after executing the driver. I wasn't able to reproduce the problem when running the driver; each time I run the driver, the `BTMain` actor receives all 10 expected `OperationReply` messages and each reply has the correct value.

Requests sent after a GC request should be routed to the actor representing the new root of the tree, which in turn forwards messages to its children. In the driver, requests 7-10 are sent after the GC request. According to the logs, the new root and its children are the only nodes that receive messages corresponding to requests 7-10 (see lines 34-43 in the logs). This is consistent with the expected outcome.

Requests sent to the tree while garbage collection is in progress should not be serviced until after garbage collection completes. According to the logs, the actors representing the new root and its children don't receive any `Contains` messages until after the tree receives the `CopyFinished` message to signify that GC finished (see line 33 and lines 34-43 in the logs). This is consistent with the expected outcome.

I don't know what's causing the third test to fail.
