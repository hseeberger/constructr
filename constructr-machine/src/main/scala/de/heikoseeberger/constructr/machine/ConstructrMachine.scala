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

  case class Data[N, B <: Coordination.Backend](nodes: List[N], nrOfAddSelfRetriesLeft: Int, context: B#Context)

  private final case class Retry(state: State)

  final case class StateTimeoutException(state: State) extends RuntimeException(s"State timeout triggered in state $state!")

  final val Name = "constructr-machine"

  def props[N: Coordination.NodeSerialization, B <: Coordination.Backend](
    selfNode: N,
    coordination: Coordination[B],
    coordinationTimeout: FiniteDuration,
    nrOfAddSelfRetries: Int,
    retryGetNodesDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: Option[FiniteDuration] = None,
    intoJoiningHandler: ConstructrMachine[N, B] => Unit = (machine: ConstructrMachine[N, B]) => (),
    joiningFunction: ConstructrMachine[N, B] => StateFunction[N, B] = (machine: ConstructrMachine[N, B]) => { case machine.Event(machine.StateTimeout, _) => machine.goto(State.AddingSelf) }: StateFunction[N, B],
    outOfJoiningHandler: ConstructrMachine[N, B] => Unit = (machine: ConstructrMachine[N, B]) => ()
  ): Props =
    Props(new ConstructrMachine[N, B](
      selfNode,
      coordination,
      coordinationTimeout,
      nrOfAddSelfRetries,
      retryGetNodesDelay,
      refreshInterval,
      ttlFactor,
      maxNrOfSeedNodes,
      joinTimeout,
      intoJoiningHandler,
      joiningFunction,
      outOfJoiningHandler
    ))
}

final class ConstructrMachine[N: Coordination.NodeSerialization, B <: Coordination.Backend] private (
  val selfNode: N,
  coordination: Coordination[B],
  coordinationTimeout: FiniteDuration,
  nrOfAddSelfRetries: Int,
  retryGetNodesDelay: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double,
  maxNrOfSeedNodes: Int,
  joinTimeout: Option[FiniteDuration],
  intoJoiningHandler: ConstructrMachine[N, B] => Unit,
  joiningFunction: ConstructrMachine[N, B] => ConstructrMachine.StateFunction[N, B],
  outOfJoiningHandler: ConstructrMachine[N, B] => Unit
)
    extends FSM[ConstructrMachine.State, ConstructrMachine.Data[N, B]] {
  import ConstructrMachine._
  import context.dispatcher

  private implicit val mat = ActorMaterializer()

  private val overallCoordinationTimeout = coordinationTimeout * (1 + nrOfAddSelfRetries)

  require(maxNrOfSeedNodes > 0, s"max-nr-of-seed-nodes must be positive, but was $maxNrOfSeedNodes!")
  require(
    ttlFactor > 1 + overallCoordinationTimeout / refreshInterval,
    s"ttl-factor must be greater than 1 + (coordination-timeout * (1 + coordination-retries) / refresh-interval), but was $ttlFactor!"
  )

  private val addOrRefreshTtl = refreshInterval * ttlFactor

  startWith(State.GettingNodes, Data(Nil, nrOfAddSelfRetries, coordination.initialBackendContext))

  // Getting nodes

  onTransition {
    case State.BeforeGettingNodes -> State.GettingNodes =>
      log.debug("Transitioning to GettingNodes")
      coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(Nil, data) =>
      log.debug("Received empty nodes, going to Locking")
      goto(State.Locking).using(data.copy(Nil))

    case Event(nodes: List[N] @unchecked, data) =>
      val seedNodes = nodes.take(maxNrOfSeedNodes)
      log.debug(s"Received nodes $nodes, using seed nodes $seedNodes, going to Joining")
      goto(State.Joining).using(data.copy(seedNodes))
  }

  // Locking

  onTransition {
    case _ -> State.Locking =>
      log.debug("Transitioning to Locking")
      val ttl = (2 * overallCoordinationTimeout + joinTimeout.getOrElse(Duration.Zero)) * ttlFactor // Keep lock until self added
      coordination.lock(selfNode, ttl).pipeTo(self)
  }

  when(State.Locking, coordinationTimeout) {
    case Event(Coordination.LockResult.Success, data) =>
      log.debug("Successfully locked, going to Joining")
      goto(State.Joining).using(data.copy(List(selfNode)))

    case Event(Coordination.LockResult.Failure, data) =>
      log.debug("Couldn't lock, going to BeforeGettingNodes")
      goto(State.BeforeGettingNodes).using(data.copy(Nil))
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
      coordination.addSelf(selfNode, addOrRefreshTtl).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Coordination.SelfAdded(context: B#Context @unchecked), data) =>
      log.debug("Successfully added self, going to RefreshScheduled")
      goto(State.RefreshScheduled).using(data.copy(context = context))
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
    case Event(Coordination.Refreshed(_), _) =>
      log.debug("Successfully refreshed, going to RefreshScheduled")
      goto(State.RefreshScheduled)
  }

  // Handle failure

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error(cause, "Unexpected failure!")
      throw cause

    case Event(StateTimeout, data @ Data(_, n, _)) =>
      stateName match {
        case State.AddingSelf if n > 0 =>
          log.warning(s"Coordination timout in state ${State.AddingSelf}, $n retries left!")
          goto(stateName).using(data.copy(nrOfAddSelfRetriesLeft = n - 1))

        case State.AddingSelf =>
          log.error(s"Coordination timeout in state ${State.AddingSelf}, no retries left!")
          throw ConstructrMachine.StateTimeoutException(stateName)

        case _ =>
          log.warning(s"Coordination timout in state $stateName, retrying!")
          goto(stateName)
      }
  }

  // Initialization

  initialize()
  coordination.getNodes().pipeTo(self)
}
