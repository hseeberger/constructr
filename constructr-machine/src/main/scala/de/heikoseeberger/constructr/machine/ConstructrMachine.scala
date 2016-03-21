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

import akka.Done
import akka.actor.{ FSM, Props, Status }
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination
import scala.concurrent.duration.FiniteDuration

object ConstructrMachine {

  type StateFunction[A] = PartialFunction[FSM.Event[ConstructrMachine.Data[A]], FSM.State[ConstructrMachine.State, ConstructrMachine.Data[A]]]

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

  case class Data[A](nodes: Set[A], retryState: State, nrOfRetriesLeft: Int)

  final case class StateTimeoutException(state: State) extends RuntimeException(s"State timeout triggered in state $state!")

  final val Name = "constructr-machine"

  def props[A: Coordination.NodeSerialization](
    selfNode: A,
    coordination: Coordination,
    coordinationTimeout: FiniteDuration,
    coordinationRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: FiniteDuration,
    intoJoiningHandler: ConstructrMachine[A] => Unit,
    joiningFunction: ConstructrMachine[A] => StateFunction[A],
    outOfJoiningHandler: ConstructrMachine[A] => Unit
  ): Props = Props(new ConstructrMachine[A](
    selfNode,
    coordination,
    coordinationTimeout,
    coordinationRetries,
    retryDelay,
    refreshInterval,
    ttlFactor,
    maxNrOfSeedNodes,
    joinTimeout,
    intoJoiningHandler,
    joiningFunction,
    outOfJoiningHandler
  ))
}

final class ConstructrMachine[A: Coordination.NodeSerialization](
    val selfNode: A,
    coordination: Coordination,
    coordinationTimeout: FiniteDuration,
    nrOfRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: FiniteDuration,
    intoJoiningHandler: ConstructrMachine[A] => Unit,
    joiningFunction: ConstructrMachine[A] => ConstructrMachine.StateFunction[A],
    outOfJoiningHandler: ConstructrMachine[A] => Unit
) extends FSM[ConstructrMachine.State, ConstructrMachine.Data[A]] {
  import ConstructrMachine._
  import context.dispatcher

  require(maxNrOfSeedNodes > 0, s"max-nr-of-seed-nodes must be positive, but was $maxNrOfSeedNodes!")
  require(
    ttlFactor >= minTtlFactor,
    s"ttl-factor must be greater or equal 1 + ((coordination-timeout * (1 + nr-of-retries) + retry-delay * nr-of-retries)/ refresh-interval), i.e. $minTtlFactor, but was $ttlFactor!"
  )

  private implicit val mat = ActorMaterializer()

  startWith(State.GettingNodes, Data(Set.empty, State.GettingNodes, nrOfRetries))

  // Getting nodes

  onTransition {
    case _ -> State.GettingNodes =>
      log.debug("Transitioning to GettingNodes")
      coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(nodes: Set[A] @unchecked, _) if nodes.isEmpty =>
      log.debug("Received empty nodes, going to Locking")
      goto(State.Locking).using(stateData.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(nodes: Set[A] @unchecked, _) =>
      log.debug(s"Received nodes $nodes, going to Joining")
      goto(State.Joining).using(stateData.copy(nodes = nodes, nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to RetryScheduled/GettingNodes: $cause")
      retry(State.GettingNodes)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to RetryScheduled/GettingNodes")
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
    case Event(true, _) =>
      log.debug("Successfully locked, going to Joining")
      goto(State.Joining).using(stateData.copy(nodes = Set(selfNode), nrOfRetriesLeft = nrOfRetries))

    case Event(false, _) =>
      log.warning("Couldn't acquire lock, going to GettingNodes")
      goto(State.GettingNodes).using(stateData.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to RetryScheduled/Locking: $cause")
      retry(State.Locking)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to RetryScheduled/Locking")
      retry(State.Locking)
  }

  // Joining

  onTransition {
    case _ -> State.Joining =>
      log.debug("Transitioning to Joining")
      intoJoiningHandler(this)
  }

  when(State.Joining, joinTimeout)(joiningFunction(this))

  onTransition {
    case State.Joining -> _ =>
      log.debug("Transitioning out of Joining")
      outOfJoiningHandler(this)
  }

  def seedNodes(nodes: Set[A]): Set[A] = nodes.take(maxNrOfSeedNodes)

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf =>
      log.debug("Transitioning to AddingSelf")
      coordination.addSelf(selfNode, addingSelfOrRefreshingTtl.toFinite).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Done, data) =>
      log.debug("Successfully added self, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(data.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to RetryScheduled/AddingSelf: $cause")
      retry(State.AddingSelf)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to RetryScheduled/AddingSelf")
      retry(State.AddingSelf)
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
      coordination.refresh(selfNode, addingSelfOrRefreshingTtl.toFinite).pipeTo(self)
  }

  when(State.Refreshing, coordinationTimeout) {
    case Event(Done, _) =>
      log.debug("Successfully refreshed, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(stateData.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to RetryScheduled/Refreshing: $cause")
      retry(State.Refreshing)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to RetryScheduled/Refreshing")
      retry(State.Refreshing)
  }

  // RetryScheduled

  onTransition {
    case state -> State.RetryScheduled => log.debug(s"Transitioning from $state to RetryScheduled")
  }

  when(State.RetryScheduled, retryDelay) {
    case Event(StateTimeout, Data(_, retryState, _)) =>
      log.debug(s"Waited for $retryDelay, going to $retryState")
      goto(retryState)
  }

  // Initialization

  initialize()

  // Helpers

  private def retry(retryState: ConstructrMachine.State) =
    if (stateData.nrOfRetriesLeft <= 0)
      stop(FSM.Failure(s"Number of retries exhausted in $stateName!"))
    else
      goto(State.RetryScheduled).using(stateData.copy(retryState = retryState, nrOfRetriesLeft = stateData.nrOfRetriesLeft - 1))

  private def maxCoordinationTimeout = coordinationTimeout * (1 + nrOfRetries) + retryDelay * nrOfRetries

  private def minTtlFactor = 1 + maxCoordinationTimeout / refreshInterval

  private def addingSelfOrRefreshingTtl = refreshInterval * ttlFactor
}
