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

import akka.actor.{ ActorRef, Props }
import de.heikoseeberger.constructr.coordination.Coordination
import de.heikoseeberger.constructr.machine.ConstructrMachine
import java.net.InetAddress
import scala.concurrent.duration.{ Duration, FiniteDuration }

object CassandraConstructrMachine {

  final val Name = "cassandra-constructr-machine"

  def props[B <: Coordination.Backend](
    selfNode: InetAddress,
    coordination: Coordination[B],
    coordinationTimeout: FiniteDuration,
    coordinationRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    constructr: ActorRef
  ): Props = Props(new CassandraConstructrMachine[B](
    selfNode,
    coordination,
    coordinationTimeout,
    coordinationRetries,
    retryDelay,
    refreshInterval,
    ttlFactor,
    maxNrOfSeedNodes,
    constructr
  ))
}

final class CassandraConstructrMachine[B <: Coordination.Backend](
  selfNode: InetAddress,
  coordination: Coordination[B],
  coordinationTimeout: FiniteDuration,
  coordinationRetries: Int,
  retryDelay: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double,
  maxNrOfSeedNodes: Int,
  constructr: ActorRef
) extends ConstructrMachine(
  selfNode,
  coordination,
  coordinationTimeout,
  coordinationRetries,
  retryDelay,
  refreshInterval,
  ttlFactor,
  maxNrOfSeedNodes,
  Duration.Zero
) {
  import ConstructrMachine._

  override protected def intoJoiningHandler() = constructr ! Constructr.Nodes(seedNodes(nextStateData.nodes))

  override protected def joiningFunction = { case Event(StateTimeout, _) => goto(State.AddingSelf) }

  override protected def outOfJoiningHandler() = ()
}
