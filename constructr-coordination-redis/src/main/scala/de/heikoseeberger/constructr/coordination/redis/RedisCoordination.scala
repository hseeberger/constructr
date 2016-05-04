/*
 * Copyright 2016 Shingo Omura
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
package redis

import java.util.Base64

import _root_.redis.RedisClient
import akka.Done
import akka.actor.{ ActorRefFactory, ActorSystem }
import com.typesafe.config.{ Config, ConfigException }
import de.heikoseeberger.constructr.coordination.Coordination.NodeSerialization

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

final class RedisCoordination(
    val prefix: String,
    val clusterName: String,
    val system: ActorSystem
) extends Coordination {
  implicit val _system: ActorRefFactory = system
  implicit val _ec = _system.dispatcher

  val redis = RedisClientFactory.fromConfig(system.settings.config)

  def lockKey = s"${prefix}/${clusterName}/lock"
  def nodeKeyPrefix = s"${prefix}/${clusterName}/nodes"
  def nodeKey[A: NodeSerialization](self: A) = s"${nodeKeyPrefix}/${Base64.getUrlEncoder.encodeToString(NodeSerialization.toBytes(self))}"

  /**
   * Get the nodes.
   *
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return future of nodes
   */
  override def getNodes[A: NodeSerialization](): Future[Set[A]] = redis.keys(s"${nodeKeyPrefix}/*").map(ks => scala.collection.immutable.Seq(ks: _*)).flatMap { ks =>
    // N+1 problem would happen here.
    // But N might not be so large in real situation.
    val f: Seq[Future[Option[A]]] = for {
      k <- ks
    } yield {
      for {
        bytesOpt <- redis.get[Array[Byte]](k)
      } yield {
        bytesOpt.map(NodeSerialization.fromBytes[A])
      }
    }
    Future.sequence(f).map { opts =>
      opts.filter(_.nonEmpty)
    }.map { somes =>
      somes.map(_.get)
    }.map {
      _.toSet
    }
  }

  /**
   * Akquire a lock for bootstrapping the cluster (first node).
   *
   * @param self self node
   * @param ttl  TTL for the lock
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return true, if lock could be akquired, else false
   */
  override def lock[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Boolean] = {
    def readLock(): Future[Option[A]] = redis.get[Array[Byte]](lockKey).map(_.map(NodeSerialization.fromBytes[A]))
    def writeLock(lockHolder: A): Future[Boolean] = {
      val trans2 = redis.watch(lockKey)
      val writeLock = trans2.set(lockKey, NodeSerialization.toBytes(lockHolder), None, Some(ttl.toMillis))
      trans2.exec()
      writeLock.recover {
        case error => false
      }
    }
    readLock().flatMap {
      case Some(lockHolder) if lockHolder != self => Future.successful(false)
      case _                                      => writeLock(self)
    }
  }

  /**
   * Refresh entry for self.
   *
   * @param self self node
   * @param ttl  TTL for the node entry
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return future signaling done
   */
  override def refresh[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Done] = addSelfOrRefresh(self, ttl)

  /**
   * Add self to the nodes.
   *
   * @param self self node
   * @param ttl  TTL for the node entry
   * @tparam A node type, must have a [[Coordination.NodeSerialization]]
   * @return future signaling done
   */
  override def addSelf[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Done] = addSelfOrRefresh(self, ttl)

  // redis doesn't support element-wise ttl in [sorted] sets.
  // So, this sets with
  //   key -> {prefix}/{clusterName}/nodes/{encoded self}
  //   value -> encoded self
  private def addSelfOrRefresh[A: NodeSerialization](self: A, ttl: FiniteDuration): Future[Done] = redis.set(nodeKey(self), NodeSerialization.toBytes(self), None, Some(ttl.toMillis)).map(_ => Done)

}

object RedisClientFactory {
  def fromConfig(config: Config)(implicit af: ActorRefFactory): RedisClient = {
    val host = Option(config.getString("constructr.coordination.host")).filter(_.trim.nonEmpty).getOrElse("")
    val port = config.getInt("constructr.coordination.port")
    require(host.nonEmpty, "\"constructr.coordination.redis.host\" must be given.")
    require(port > 0, "\"constructr.coordination.redis.port\" must be positive integer.")

    val password = try {
      Option(config.getString("constructr.coordination.redis.password")).filter(_.nonEmpty)
    } catch {
      case e: ConfigException.Missing => None
    }
    val db = try {
      Option(config.getInt("constructr.coordination.redis.db"))
    } catch {
      case e: ConfigException.Missing => None
    }

    RedisClient(host, port, password, db)
  }

}