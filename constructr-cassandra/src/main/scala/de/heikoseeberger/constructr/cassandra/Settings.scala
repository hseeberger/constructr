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

package de.heikoseeberger.constructr.cassandra

import akka.actor.{ Actor, ExtendedActorSystem, Extension, ExtensionKey }
import de.heikoseeberger.constructr.coordination.Coordination
import java.net.InetAddress
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }

object Settings extends ExtensionKey[Settings]

final class Settings private (system: ExtendedActorSystem) extends Extension {

  object coordination {

    val backend: Coordination.Backend = {
      config.getString("coordination.backend").toLowerCase match {
        case "etcd" => Coordination.Backend.Etcd
        case other  => throw new IllegalArgumentException(s"Unknown coordination backend $other!")
      }
    }

    val host: String = config.getString("coordination.host")

    val port: Int = config.getInt("coordination.port")
  }

  val selfAddress: InetAddress = {
    val selfAddress = config.getString("self-address")
    if (selfAddress.toLowerCase == "auto") InetAddress.getLocalHost else InetAddress.getByName(selfAddress)
  }

  val clusterName: String = config.getString("cluster-name")

  val seedProviderTimeout: FiniteDuration = getDuration("seed-provider-timeout")

  val coordinationTimeout: FiniteDuration = getDuration("coordination-timeout")

  val refreshInterval: FiniteDuration = getDuration("refresh-interval")

  val retryGetNodesDelay: FiniteDuration = getDuration("retry-get-nodes-delay")

  val ttlFactor: Double = {
    val ttlFactor = config.getDouble("ttl-factor")
    require(
      ttlFactor > 1 + coordinationTimeout / refreshInterval,
      s"ttl-factor must be greater than one plus coordination-timeout divided by refresh-interval, but was $ttlFactor!"
    )
    ttlFactor
  }

  private def config = system.settings.config.getConfig("constructr.cassandra")

  private def getDuration(key: String) = FiniteDuration(config.getDuration(key, MILLISECONDS), MILLISECONDS)
}

trait ActorSettings { this: Actor =>
  final val settings: Settings = Settings(context.system)
}
