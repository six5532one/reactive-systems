/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import BinaryTreeSet._
import akka.actor._

class BTMain extends Actor {
  val tree = context.actorOf(Props[BinaryTreeSet], "tree")
  tree ! Insert(self, 1, 30)
  tree ! Contains(self, 2, 30)
  tree ! Insert(self, 3, -20)
  tree ! Insert(self, 4, -50)
  tree ! Insert(self, 5, -10)
  tree ! Remove(self, 6, -20)
  tree ! GC
  tree ! Contains(self, 7, -50)
  tree ! Contains(self, 8, -10)
  tree ! Contains(self, 9, -20)
  tree ! Contains(self, 10, 30)

  def receive: Receive = {
    case msg => println(s"Main received $msg")
  }
}