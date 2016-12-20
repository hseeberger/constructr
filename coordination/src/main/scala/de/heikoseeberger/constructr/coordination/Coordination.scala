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
import akka.actor.{ ActorSystem, Address }
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object Coordination {

  def apply(clusterName: String, system: ActorSystem): Coordination =
    try {
      val className =
        system.settings.config.getString("constructr.coordination.class-name")
      Class
        .forName(className)
        .getConstructor(classOf[String], classOf[ActorSystem])
        .newInstance(clusterName, system)
        .asInstanceOf[Coordination]
    } catch {
      case _: NoSuchMethodException =>
        throw new Exception(
          """|A Coordination implementation must provide a constructor with the following signature:
             |(clusterName: String, system: ActorSystem)""".stripMargin
        )
    }
}

/**
  * Abstraction for a coordination service. Implementations must provide a constructor with the following signature:
  * `(prefix: String, clusterName: String, system: ActorSystem)`.
  */
trait Coordination {

  /**
    * Get the nodes.
    *
    * @return future of nodes
    */
  def getNodes(): Future[Set[Address]]

  /**
    * Akquire a lock for bootstrapping the cluster (first node).
    *
    * @param self self node
    * @param ttl TTL for the lock
    * @return true, if lock could be akquired, else false
    */
  def lock(self: Address, ttl: FiniteDuration): Future[Boolean]

  /**
    * Add self to the nodes.
    *
    * @param self self node
    * @param ttl TTL for the node entry
    * @return future signaling done
    */
  def addSelf(self: Address, ttl: FiniteDuration): Future[Done]

  /**
    * Refresh entry for self.
    *
    * @param self self node
    * @param ttl TTL for the node entry
    * @return future signaling done
    */
  def refresh(self: Address, ttl: FiniteDuration): Future[Done]
}
