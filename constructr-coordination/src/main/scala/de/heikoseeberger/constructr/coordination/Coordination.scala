/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.heikoseeberger.constructr.coordination

import akka.Done
import akka.actor.ActorSystem
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object Coordination {

  object NodeSerialization {
    def fromBytes[A: NodeSerialization](bytes: Array[Byte]): A = implicitly[NodeSerialization[A]].fromBytes(bytes)
    def toBytes[A: NodeSerialization](n: A): Array[Byte] = implicitly[NodeSerialization[A]].toBytes(n)
  }

  /**
   * Type class for serializing nodes.
   *
   * @tparam A node type
   */
  trait NodeSerialization[A] {
    def fromBytes(bytes: Array[Byte]): A
    def toBytes(n: A): Array[Byte]
  }

  def apply(prefix: String, clusterName: String, system: ActorSystem): Coordination =
    try
      Class
        .forName(system.settings.config.getString("constructr.coordination.class-name"))
        .getConstructor(classOf[String], classOf[String], classOf[ActorSystem])
        .newInstance(prefix, clusterName, system)
        .asInstanceOf[Coordination]
    catch {
      case _: NoSuchMethodException =>
        throw new Exception(
          """|A Coordination implementation must provide a constructor with the following signature:
             |(prefix: String, clusterName: String, system: ActorSystem)""".stripMargin
        )
    }
}

/**
 * Abstraction for a coordination service. Implementations must provide a constructor with the following signature:
 * `(prefix: String, clusterName: String, system: ActorSystem)`.
 */
trait Coordination {
  import Coordination._

  /**
   * Get the nodes.
   *
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return future of nodes
   */
  def getNodes[A: NodeSerialization](): Future[Set[A]]

  /**
   * Akquire a lock for bootstrapping the cluster (first node).
   *
   * @param self self node
   * @param ttl TTL for the lock
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return true, if lock could be akquired, else false
   */
  def lock[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Boolean]

  /**
   * Add self to the nodes.
   *
   * @param self self node
   * @param ttl TTL for the node entry
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return future signaling done
   */
  def addSelf[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Done]

  /**
   * Refresh entry for self.
   *
   * @param self self node
   * @param ttl TTL for the node entry
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return future signaling done
   */
  def refresh[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Done]
}
