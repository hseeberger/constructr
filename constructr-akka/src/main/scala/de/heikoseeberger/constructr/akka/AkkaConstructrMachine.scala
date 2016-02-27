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

package de.heikoseeberger.constructr.akka

import akka.actor.{ Address, FSM, Props }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ InitialStateAsEvents, MemberJoined, MemberUp }
import de.heikoseeberger.constructr.coordination.Coordination
import de.heikoseeberger.constructr.machine.ConstructrMachine
import scala.concurrent.duration.FiniteDuration

object AkkaConstructrMachine {

  final val Name = "akka-constructr-machine"

  def props[B <: Coordination.Backend](
    selfNode: Address,
    coordination: Coordination[B],
    coordinationTimeout: FiniteDuration,
    coordinationRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: FiniteDuration
  ): Props = Props(new AkkaConstructrMachine[B](
    selfNode,
    coordination,
    coordinationTimeout,
    coordinationRetries,
    retryDelay,
    refreshInterval,
    ttlFactor,
    maxNrOfSeedNodes,
    joinTimeout
  ))
}

final class AkkaConstructrMachine[B <: Coordination.Backend](
  selfNode: Address,
  coordination: Coordination[B],
  coordinationTimeout: FiniteDuration,
  coordinationRetries: Int,
  retryDelay: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double,
  maxNrOfSeedNodes: Int,
  joinTimeout: FiniteDuration
) extends ConstructrMachine(
  selfNode,
  coordination,
  coordinationTimeout,
  coordinationRetries,
  retryDelay,
  refreshInterval,
  ttlFactor,
  maxNrOfSeedNodes,
  joinTimeout
) {
  import ConstructrMachine._

  override protected def intoJoiningHandler() = {
    Cluster(context.system).joinSeedNodes(seedNodes(nextStateData.nodes)) // An existing seed node process would be stopped
    Cluster(context.system).subscribe(self, InitialStateAsEvents, classOf[MemberJoined], classOf[MemberUp])
  }

  override protected def joiningFunction = {
    case Event(MemberJoined(member), _) if member.address == selfNode => goto(State.AddingSelf)
    case Event(MemberJoined(member), _)                               => stay()
    case Event(MemberUp(member), _) if member.address == selfNode     => goto(State.AddingSelf)
    case Event(MemberUp(member), _)                                   => stay()
    case Event(StateTimeout, _)                                       => stop(FSM.Failure("Timeout in Joining!"))
  }

  override protected def outOfJoiningHandler() = Cluster(context.system).unsubscribe(self)
}
