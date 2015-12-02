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

import akka.actor.{ FSM, Props, Status }
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination
import scala.concurrent.duration.{ Duration, FiniteDuration }

object ConstructrMachine {

  type TransitionHandler[A] = PartialFunction[(State, State), Unit]

  type StateFunction[A, B <: Coordination.Backend] = PartialFunction[FSM.Event[Data[A, B]], FSM.State[State, Data[A, B]]]

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

  case class Data[A, B <: Coordination.Backend](nodes: List[A], coordinationRetriesLeft: Int, context: B#Context)

  private final case class Retry(state: State)

  final case class StateTimeoutException(state: State) extends RuntimeException(s"State timeout triggered in state $state!")

  final val Name = "constructr-machine"

  def props[A: Coordination.AddressSerialization, B <: Coordination.Backend](
    selfAddress: A,
    coordination: Coordination[B],
    coordinationTimeout: FiniteDuration,
    coordinationRetries: Int,
    retryGetNodesDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    joinTimeout: Option[FiniteDuration] = None,
    intoJoiningHandler: ConstructrMachine[A, B] => Unit = (machine: ConstructrMachine[A, B]) => (),
    joiningFunction: ConstructrMachine[A, B] => StateFunction[A, B] = (machine: ConstructrMachine[A, B]) => { case machine.Event(machine.StateTimeout, _) => machine.goto(State.AddingSelf) }: StateFunction[A, B],
    outOfJoiningHandler: ConstructrMachine[A, B] => Unit = (machine: ConstructrMachine[A, B]) => ()
  ): Props =
    Props(new ConstructrMachine[A, B](
      selfAddress,
      coordination,
      coordinationTimeout,
      coordinationRetries,
      retryGetNodesDelay,
      refreshInterval,
      ttlFactor,
      joinTimeout,
      intoJoiningHandler,
      joiningFunction,
      outOfJoiningHandler
    ))
}

final class ConstructrMachine[A: Coordination.AddressSerialization, B <: Coordination.Backend] private (
  val selfAddress: A,
  coordination: Coordination[B],
  coordinationTimeout: FiniteDuration,
  coordinationRetries: Int,
  retryGetNodesDelay: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double,
  joinTimeout: Option[FiniteDuration],
  intoJoiningHandler: ConstructrMachine[A, B] => Unit,
  joiningFunction: ConstructrMachine[A, B] => ConstructrMachine.StateFunction[A, B],
  outOfJoiningHandler: ConstructrMachine[A, B] => Unit
)
    extends FSM[ConstructrMachine.State, ConstructrMachine.Data[A, B]] {
  import ConstructrMachine._
  import context.dispatcher

  private implicit val mat = ActorMaterializer()

  private val overallCoordinationTimeout = coordinationTimeout * (1 + coordinationRetries)

  require(
    ttlFactor > 1 + overallCoordinationTimeout / refreshInterval,
    s"ttl-factor must be greater than 1 + (coordination-timeout * (1 + coordination-retries) / refresh-interval), but was $ttlFactor!"
  )

  private val addOrRefreshTtl = refreshInterval * ttlFactor

  startWith(State.GettingNodes, Data(Nil, coordinationRetries, coordination.initialBackendContext))

  // Getting nodes

  onTransition {
    case State.BeforeGettingNodes -> State.GettingNodes =>
      log.debug("Transitioning to GettingNodes")
      coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(Nil, data) =>
      log.debug("Received empty nodes, going to Locking")
      goto(State.Locking).using(data.copy(Nil, coordinationRetries))

    case Event(nodes: List[A] @unchecked, data) =>
      log.debug(s"Received nodes $nodes, going to Joining")
      goto(State.Joining).using(data.copy(nodes, coordinationRetries))
  }

  // Locking

  onTransition {
    case _ -> State.Locking =>
      log.debug("Transitioning to Locking")
      val ttl = (2 * overallCoordinationTimeout + joinTimeout.getOrElse(Duration.Zero)) * ttlFactor // Keep lock until self added
      coordination.lock(ttl).pipeTo(self)
  }

  when(State.Locking, coordinationTimeout) {
    case Event(Coordination.LockResult.Success, data) =>
      log.debug("Successfully locked, going to Joining")
      goto(State.Joining).using(data.copy(List(selfAddress), coordinationRetries))

    case Event(Coordination.LockResult.Failure, data) =>
      log.debug("Couldn't lock, going to BeforeGettingNodes")
      goto(State.BeforeGettingNodes).using(data.copy(Nil, coordinationRetries))
  }

  // BeforeGettingNodes

  onTransition {
    case _ -> State.BeforeGettingNodes => log.debug("Transitioning to BeforeGettingNodes")
  }

  when(State.BeforeGettingNodes, retryGetNodesDelay) {
    case Event(StateTimeout, _) =>
      log.debug(s"Waited for $retryGetNodesDelay, going to GettingNodes")
      goto(State.GettingNodes)
  }

  // Joining

  onTransition {
    case _ -> State.Joining =>
      log.debug("Transitioning to Joining")
      intoJoiningHandler(this)
  }

  when(State.Joining, joinTimeout.getOrElse(Duration.Zero))(joiningFunction(this))

  onTransition {
    case State.Joining -> _ =>
      log.debug("Transitioning out of Joining")
      outOfJoiningHandler(this)
  }

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf =>
      log.debug("Transitioning to AddingSelf")
      coordination.addSelf(selfAddress, addOrRefreshTtl).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Coordination.SelfAdded(context: B#Context @unchecked), data) =>
      log.debug("Successfully added self, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(data.copy(coordinationRetriesLeft = coordinationRetries, context = context))
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
      coordination.refresh(selfAddress, addOrRefreshTtl, stateData.context).pipeTo(self)
  }

  when(State.Refreshing, coordinationTimeout) {
    case Event(Coordination.Refreshed(_), _) =>
      log.debug("Successfully refreshed, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(stateData.copy(coordinationRetriesLeft = coordinationRetries))
  }

  // Handle failure

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error(cause, "Unexpected failure!")
      throw cause

    case Event(StateTimeout, data @ Data(_, n, _)) if n > 0 =>
      log.warning(s"Coordination timout in state $stateName, $n retries left!")
      goto(stateName).using(data.copy(coordinationRetriesLeft = n - 1))

    case Event(StateTimeout, _) =>
      log.error(s"Coordination timeout in state $stateName, no retries left!")
      throw ConstructrMachine.StateTimeoutException(stateName)
  }

  // Initialization

  initialize()
  coordination.getNodes().pipeTo(self)
}
