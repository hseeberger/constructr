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
import akka.stream.Materializer
import de.heikoseeberger.constructr.coordination.Coordination
import scala.concurrent.duration.{ Duration, FiniteDuration }

object ConstructrMachine {

  type TransitionHandler[A] = PartialFunction[(State, State), Unit]

  type StateFunction[A] = PartialFunction[FSM.Event[Data[A]], FSM.State[State, Data[A]]]

  sealed trait State
  object State {
    case object GettingNodes extends State
    case object Locking extends State
    case object BeforeGettingNodes extends State
    case object Joining extends State
    case object AddingSelf extends State
    case object AddingSelfScheduled extends State
    case object Retrying extends State
  }

  case class Data[A](nodes: List[A], coordinationRetriesLeft: Int)

  private final case class Retry(state: State)

  final case class StateTimeoutException(state: State) extends RuntimeException(s"State timeout triggered in state $state!")

  final val Name = "constructr-machine"

  def defaultJoiningTransitionHandler[A](machine: ConstructrMachine[A]): TransitionHandler[A] = PartialFunction.empty

  def defaultJoiningFunction[A](machine: ConstructrMachine[A]): StateFunction[A] = {
    case machine.Event(machine.StateTimeout, _) => machine.goto(State.AddingSelf)
  }

  def noopHandler[A](machine: ConstructrMachine[A]): TransitionHandler[A] = PartialFunction.empty

  def props[A: Coordination.AddressSerialization](
    selfAddress: A,
    coordination: Coordination,
    coordinationTimeout: FiniteDuration,
    coordinationRetries: Int,
    retryGetNodesDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    joinTimeout: Option[FiniteDuration] = None,
    intoJoiningHandler: ConstructrMachine[A] => TransitionHandler[A] = noopHandler[A] _,
    joiningFunction: ConstructrMachine[A] => StateFunction[A] = defaultJoiningFunction[A] _,
    outOfJoiningHandler: ConstructrMachine[A] => TransitionHandler[A] = noopHandler[A] _
  )(implicit mat: Materializer): Props =
    Props(new ConstructrMachine[A](
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

final class ConstructrMachine[A: Coordination.AddressSerialization] private (
  val selfAddress: A,
  coordination: Coordination,
  coordinationTimeout: FiniteDuration,
  coordinationRetries: Int,
  retryGetNodesDelay: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double,
  joinTimeout: Option[FiniteDuration],
  intoJoiningHandler: ConstructrMachine[A] => ConstructrMachine.TransitionHandler[A],
  joiningFunction: ConstructrMachine[A] => ConstructrMachine.StateFunction[A],
  outOfJoiningHandler: ConstructrMachine[A] => ConstructrMachine.TransitionHandler[A]
)(implicit mat: Materializer)
    extends FSM[ConstructrMachine.State, ConstructrMachine.Data[A]] {
  import ConstructrMachine._
  import context.dispatcher

  require(
    ttlFactor > 1 + coordinationTimeout / refreshInterval,
    s"ttl-factor must be greater than one plus coordination-timeout divided by refresh-interval, but was $ttlFactor!"
  )

  private val addOrRefreshTtl = refreshInterval * ttlFactor

  startWith(State.GettingNodes, data(Nil))

  // Getting nodes

  onTransition {
    case State.BeforeGettingNodes -> State.GettingNodes => coordination.getNodes().pipeTo(self)
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(Nil, _)                       => goto(State.Locking).using(data(Nil))
    case Event(nodes: List[A] @unchecked, _) => goto(State.Joining).using(data(nodes))
  }

  // Locking

  onTransition {
    case _ -> State.Locking =>
      val ttl = (2 * coordinationTimeout + joinTimeout.getOrElse(Duration.Zero)) * ttlFactor // Keep lock until self added
      coordination.lock(ttl).pipeTo(self)
  }

  when(State.Locking, coordinationTimeout) {
    case Event(Coordination.LockResult.Success, _) => goto(State.Joining).using(data(List(selfAddress)))
    case Event(Coordination.LockResult.Failure, _) => goto(State.BeforeGettingNodes).using(data(Nil))
  }

  // BeforeGettingNodes

  when(State.BeforeGettingNodes, retryGetNodesDelay) {
    case Event(StateTimeout, _) => goto(State.GettingNodes)
  }

  // Joining

  onTransition(intoJoiningHandler(this))

  when(State.Joining, joinTimeout.getOrElse(Duration.Zero))(joiningFunction(this))

  onTransition(outOfJoiningHandler(this))

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf => coordination.addSelf(selfAddress, addOrRefreshTtl).pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Coordination.SelfAdded, _) => goto(State.AddingSelfScheduled).using(stateData.copy(coordinationRetriesLeft = coordinationRetries))
  }

  // AddingSelfScheduled

  when(State.AddingSelfScheduled, refreshInterval) {
    case Event(StateTimeout, _) => goto(State.AddingSelf)
  }

  // Retrying

  onTransition {
    case state -> State.Retrying => self ! Retry(state)
  }

  when(State.Retrying) {
    case Event(Retry(state), _) => goto(state)
  }

  // Handle failure

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error(cause, "Unexpected failure!")
      throw cause
    case Event(StateTimeout, data @ Data(_, n)) if n > 0 =>
      goto(State.Retrying).using(data.copy(coordinationRetriesLeft = n - 1))
    case Event(StateTimeout, _) =>
      log.error(s"Timeout in state $stateName!")
      throw ConstructrMachine.StateTimeoutException(stateName)
  }

  // Initialization

  initialize()
  coordination.getNodes().pipeTo(self)

  // Helpers

  private def data(nodes: List[A]) = Data(nodes, coordinationRetries)
}
