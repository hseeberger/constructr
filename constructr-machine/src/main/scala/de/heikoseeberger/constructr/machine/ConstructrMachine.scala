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
import akka.stream.scaladsl.ImplicitMaterializer
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
    case object Retrying extends State
  }

  case class Data[N, B <: Coordination.Backend](nodes: List[N], nrOfRetriesLeft: Int, context: B#Context, retryState: State)

  final case class StateTimeoutException(state: State) extends RuntimeException(s"State timeout triggered in state $state!")
}

abstract class ConstructrMachine[N: Coordination.NodeSerialization, B <: Coordination.Backend](
    selfNode: N,
    coordination: Coordination[B],
    coordinationTimeout: FiniteDuration,
    nrOfRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: FiniteDuration
) extends FSM[ConstructrMachine.State, ConstructrMachine.Data[N, B]] with ImplicitMaterializer {
  import ConstructrMachine._
  import context.dispatcher

  private val overallCoordinationTimeout = coordinationTimeout * (1 + nrOfRetries)

  require(maxNrOfSeedNodes > 0, s"max-nr-of-seed-nodes must be positive, but was $maxNrOfSeedNodes!")
  require(
    ttlFactor > 1 + overallCoordinationTimeout / refreshInterval,
    s"ttl-factor must be greater than 1 + (coordination-timeout * (1 + coordination-retries) / refresh-interval), but was $ttlFactor!"
  )

  private val addOrRefreshTtl = refreshInterval * ttlFactor

  startWith(State.GettingNodes, Data(Nil, nrOfRetries, coordination.initialBackendContext, State.GettingNodes))

  // Getting nodes

  onTransition {
    case _ -> State.GettingNodes =>
      log.debug("Transitioning to GettingNodes")
      coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(Nil, _) =>
      log.debug("Received empty nodes, going to Locking")
      goto(State.Locking)

    case Event(nodes: List[N] @unchecked, _) =>
      log.debug(s"Received nodes $nodes, going to Joining")
      goto(State.Joining).using(stateData.copy(nodes = nodes))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in GettingNodes, going to Retrying for GettingNodes: $cause")
      retryGettingNodes()

    case Event(StateTimeout, _) =>
      log.warning("Timeout in GettingNodes, going to Retrying for GettingNodes")
      retryGettingNodes()
  }

  private def retryGettingNodes() =
    goto(State.Retrying).using(stateData.copy(retryState = State.GettingNodes))

  // Locking

  onTransition {
    case _ -> State.Locking =>
      log.debug("Transitioning to Locking")
      val ttl = (2 * overallCoordinationTimeout + joinTimeout) * ttlFactor // Keep lock until self added
      coordination.lock(selfNode, ttl).pipeTo(self)
  }

  when(State.Locking, coordinationTimeout) {
    case Event(Coordination.LockResult.Success, data) =>
      log.debug("Successfully locked, going to Joining")
      goto(State.Joining).using(data.copy(nodes = List(selfNode)))

    case Event(Coordination.LockResult.Failure, _) =>
      log.warning("Couldn't acquire lock, going to Retrying for GettingNodes")
      retryInLocking()

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in Locking, going to Retrying for GettingNodes: $cause")
      retryInLocking()

    case Event(StateTimeout, _) =>
      log.warning("Timeout in Locking, going to Retrying for GettingNodes")
      retryInLocking()
  }

  private def retryInLocking() =
    goto(State.Retrying).using(stateData.copy(retryState = State.GettingNodes))

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

  final protected def seedNodes(nodes: List[N]): List[N] = nodes.take(maxNrOfSeedNodes)

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf =>
      log.debug("Transitioning to AddingSelf")
      coordination.addSelf(selfNode, addOrRefreshTtl).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Coordination.SelfAdded(context: B#Context @unchecked), data) =>
      log.debug("Successfully added self, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(data.copy(context = context, nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in AddingSelf, going to Retrying for AddingSelf: $cause")
      retryAddingSelf()

    case Event(StateTimeout, _) =>
      log.warning("Timeout in AddingSelf, going to Retrying for AddingSelf")
      retryAddingSelf()
  }

  private def retryAddingSelf() = {
    val nrOfRetriesLeft = stateData.nrOfRetriesLeft
    if (nrOfRetriesLeft > 0)
      goto(State.Retrying).using(stateData.copy(nrOfRetriesLeft = nrOfRetriesLeft - 1, retryState = State.AddingSelf))
    else
      throw new IllegalStateException("Number of retries exhausted")
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
      coordination.refresh(selfNode, addOrRefreshTtl, stateData.context).pipeTo(self)
  }

  when(State.Refreshing, coordinationTimeout) {
    case Event(Coordination.Refreshed, _) =>
      log.debug("Successfully refreshed, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(stateData.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in AddingSelf, going to Retrying for AddingSelf: $cause")
      retryRefreshing()

    case Event(StateTimeout, _) =>
      log.warning("Timeout in AddingSelf, going to Retrying for AddingSelf")
      retryRefreshing()
  }

  private def retryRefreshing() = {
    val nrOfRetriesLeft = stateData.nrOfRetriesLeft
    if (nrOfRetriesLeft > 0)
      goto(State.Retrying).using(stateData.copy(nrOfRetriesLeft = nrOfRetriesLeft - 1, retryState = State.Refreshing))
    else
      throw new IllegalStateException("Number of retries exhausted")
  }

  // Retrying

  onTransition {
    case state -> State.Retrying => log.debug(s"Transitioning from $state to Retrying")
  }

  when(State.Retrying, retryDelay) {
    case Event(StateTimeout, Data(_, _, _, retryState)) =>
      log.debug(s"Waited for $retryDelay, going to $retryState")
      goto(retryState)
  }

  // Initialization

  initialize()
  coordination.getNodes().pipeTo(self)
}
