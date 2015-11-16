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

import akka.actor.{ ActorLogging, Address, FSM, Props, Status }
import akka.cluster.{ Cluster, ClusterEvent }
import akka.pattern.pipe
import akka.stream.Materializer
import de.heikoseeberger.constructr.coordination.Coordination
import scala.concurrent.duration.FiniteDuration

object ConstructrMachine {

  sealed trait State
  object State {
    case object GettingNodes extends State
    case object Locking extends State
    case object BeforeGettingNodes extends State
    case object Joining extends State
    case object AddingSelf extends State
    case object RefreshScheduled extends State
    case object Refreshing extends State
  }

  final case class StateTimeout(state: State) extends RuntimeException(s"State timeout triggered in state $state!")

  final val Name = "constructr-machine"

  def props(
    coordination: Coordination,
    coordinationTimeout: FiniteDuration,
    retryGetNodesDelay: FiniteDuration,
    joinTimeout: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double
  )(implicit mat: Materializer): Props =
    Props(new ConstructrMachine(coordination, coordinationTimeout, retryGetNodesDelay, joinTimeout, refreshInterval, ttlFactor))
}

final class ConstructrMachine(
  coordination: Coordination,
  coordinationTimeout: FiniteDuration,
  retryGetNodesDelay: FiniteDuration,
  joinTimeout: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double
)(implicit mat: Materializer)
    extends FSM[ConstructrMachine.State, List[Address]] with ActorLogging {
  import ConstructrMachine._
  import context.dispatcher

  require(
    ttlFactor > 1 + coordinationTimeout / refreshInterval,
    s"ttl-factor must be greater than one plus coordination-timeout divided by refresh-interval, but was $ttlFactor!"
  )

  private val cluster = Cluster(context.system)

  private val addOrRefreshTtl = refreshInterval * ttlFactor

  startWith(State.GettingNodes, Nil)

  // Getting nodes

  onTransition {
    case _ -> State.GettingNodes => coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(Nil, _)                             => goto(State.Locking)
    case Event(nodes: List[Address] @unchecked, _) => goto(State.Joining).using(nodes)
  }

  // Locking

  onTransition {
    case _ -> State.Locking =>
      val ttl = 2 * coordinationTimeout + joinTimeout // Keep lock until self added
      coordination.lock(ttl).pipeTo(self)
  }

  when(State.Locking, coordinationTimeout) {
    case Event(Coordination.LockResult.Success, _) => goto(State.Joining).using(List(cluster.selfAddress))
    case Event(_, _)                               => goto(State.BeforeGettingNodes)
  }

  // BeforeGettingNodes

  when(State.BeforeGettingNodes, retryGetNodesDelay) {
    case Event(StateTimeout, _) => goto(State.GettingNodes)
  }

  // Joining

  onTransition {
    case _ -> State.Joining =>
      cluster.joinSeedNodes(nextStateData) // An existing seed node process would be stopped
      cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.MemberUp])
  }

  when(State.Joining, joinTimeout) {
    case Event(ClusterEvent.MemberUp(member), _) if member.address == cluster.selfAddress => goto(State.AddingSelf)
  }

  onTransition {
    case State.Joining -> _ => cluster.unsubscribe(self)
  }

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf => coordination.addSelf(cluster.selfAddress, addOrRefreshTtl).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Coordination.SelfAdded, _) => goto(State.RefreshScheduled)
  }

  // RefreshScheduled

  when(State.RefreshScheduled, refreshInterval) {
    case Event(StateTimeout, _) => goto(State.Refreshing)
  }

  // Refreshing

  onTransition {
    case _ -> State.Refreshing => coordination.refresh(cluster.selfAddress, addOrRefreshTtl).pipeTo(self)
  }

  when(State.Refreshing, coordinationTimeout) {
    case Event(Coordination.Refreshed, _) => goto(State.RefreshScheduled)
  }

  // Handle failure

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error(cause, "Unexpected failure!")
      throw cause
    case Event(StateTimeout, _) =>
      log.error(s"Timeout in state $stateName!")
      throw ConstructrMachine.StateTimeout(stateName)
    case Event(ClusterEvent.MemberUp(_), _) =>
      stay() // Avoid warnings
  }

  initialize()
}
