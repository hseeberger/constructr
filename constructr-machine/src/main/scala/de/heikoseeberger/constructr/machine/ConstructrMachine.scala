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

package de.heikoseeberger.constructr.machine

import akka.actor.{ FSM, Status }
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination
import scala.concurrent.duration.FiniteDuration

object ConstructrMachine {

  sealed trait State
  object State {
    case object GettingNodes extends State
    case object Locking extends State
    case object Joining extends State
    case object AddingSelf extends State
    case object RefreshScheduled extends State
    case object Refreshing extends State
    case object RetryScheduled extends State
  }

  case class Data[N, B <: Coordination.Backend](nodes: Vector[N], retryState: State, coordinationRetriesLeft: Int, context: B#Context)

  final case class StateTimeoutException(state: State) extends RuntimeException(s"State timeout triggered in state $state!")
}

abstract class ConstructrMachine[N: Coordination.NodeSerialization, B <: Coordination.Backend](
    selfNode: N,
    coordination: Coordination[B],
    coordinationTimeout: FiniteDuration,
    coordinationRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: FiniteDuration
) extends FSM[ConstructrMachine.State, ConstructrMachine.Data[N, B]] {
  import ConstructrMachine._
  import context.dispatcher

  private val maxCoordinationTimeout = coordinationTimeout * (1 + coordinationRetries)

  require(maxNrOfSeedNodes > 0, s"max-nr-of-seed-nodes must be positive, but was $maxNrOfSeedNodes!")
  require(
    ttlFactor > 1 + maxCoordinationTimeout / refreshInterval,
    s"ttl-factor must be greater than 1 + (coordination-timeout * (1 + coordination-retries) / refresh-interval), but was $ttlFactor!"
  )

  private val addOrRefreshTtl = refreshInterval * ttlFactor

  private implicit val mat = ActorMaterializer()

  startWith(State.GettingNodes, Data(Vector.empty, State.GettingNodes, coordinationRetries, coordination.initialBackendContext))

  // Getting nodes

  onTransition {
    case _ -> State.GettingNodes =>
      log.debug("Transitioning to GettingNodes")
      coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(Vector(), _) =>
      log.debug("Received empty nodes, going to Locking")
      goto(State.Locking)

    case Event(nodes: Vector[N] @unchecked, _) =>
      log.debug(s"Received nodes $nodes, going to Joining")
      goto(State.Joining).using(stateData.copy(nodes = nodes))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to Retrying: $cause")
      retry(State.GettingNodes)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to Retrying")
      retry(State.GettingNodes)
  }

  // Locking

  onTransition {
    case _ -> State.Locking =>
      log.debug("Transitioning to Locking")
      val ttl = (2 * maxCoordinationTimeout + joinTimeout) * ttlFactor // Keep lock until self added
      coordination.lock(selfNode, ttl.toFinite).pipeTo(self)
  }

  when(State.Locking, coordinationTimeout) {
    case Event(Coordination.LockResult.Success, data) =>
      log.debug("Successfully locked, going to Joining")
      goto(State.Joining).using(data.copy(nodes = Vector(selfNode)))

    case Event(Coordination.LockResult.Failure, _) =>
      log.warning("Couldn't acquire lock, going to Retrying")
      retry(State.GettingNodes)

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to Retrying: $cause")
      retry(State.GettingNodes)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to Retrying")
      retry(State.GettingNodes)
  }

  // Joining

  onTransition {
    case _ -> State.Joining =>
      log.debug("Transitioning to Joining")
      intoJoiningHandler()
  }

  when(State.Joining, joinTimeout)(joiningFunction)

  onTransition {
    case State.Joining -> _ =>
      log.debug("Transitioning out of Joining")
      outOfJoiningHandler()
  }

  protected def intoJoiningHandler(): Unit

  protected def joiningFunction: StateFunction

  protected def outOfJoiningHandler(): Unit

  final protected def seedNodes(nodes: Vector[N]): Vector[N] = nodes.take(maxNrOfSeedNodes)

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf =>
      log.debug("Transitioning to AddingSelf")
      coordination.addSelf(selfNode, addOrRefreshTtl.toFinite).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Coordination.SelfAdded(context: B#Context @unchecked), data) =>
      log.debug("Successfully added self, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(data.copy(context = context, coordinationRetriesLeft = coordinationRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to Retrying: $cause")
      retry(State.GettingNodes, stateData.coordinationRetriesLeft - 1)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to Retrying")
      retry(State.GettingNodes, stateData.coordinationRetriesLeft - 1)
  }

  // RefreshScheduled

  onTransition {
    case _ -> State.RefreshScheduled => log.debug("Transitioning to RefreshScheduled")
  }

  when(State.RefreshScheduled, refreshInterval) {
    case Event(StateTimeout, _) =>
      log.debug(s"Waited for $refreshInterval, going to Refreshing")
      goto(State.Refreshing)
  }

  // Refreshing

  onTransition {
    case _ -> State.Refreshing =>
      log.debug(s"Transitioning to Refreshing")
      coordination.refresh(selfNode, addOrRefreshTtl.toFinite, stateData.context).pipeTo(self)
  }

  when(State.Refreshing, coordinationTimeout) {
    case Event(Coordination.Refreshed, _) =>
      log.debug("Successfully refreshed, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(stateData.copy(coordinationRetriesLeft = coordinationRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to Retrying: $cause")
      retry(State.GettingNodes, stateData.coordinationRetriesLeft - 1)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to Retrying")
      retry(State.GettingNodes, stateData.coordinationRetriesLeft - 1)
  }

  // RetryScheduled

  onTransition {
    case state -> State.RetryScheduled => log.debug(s"Transitioning from $state to Retrying")
  }

  when(State.RetryScheduled, retryDelay) {
    case Event(StateTimeout, Data(_, retryState, _, _)) =>
      log.debug(s"Waited for $retryDelay, going to $retryState")
      goto(retryState)
  }

  // Initialization

  initialize()
  coordination.getNodes().pipeTo(self)

  // Helpers

  private def retry(retryState: ConstructrMachine.State, coordinationRetriesLeft: Int = coordinationRetries) =
    if (coordinationRetriesLeft < 0)
      throw new IllegalStateException(s"Number of retries exhausted in $stateName!")
    else
      goto(State.RetryScheduled).using(stateData.copy(retryState = retryState, coordinationRetriesLeft = coordinationRetriesLeft))
}
