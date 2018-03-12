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

package de.heikoseeberger.constructr

import akka.Done
import akka.actor.{ Address, FSM, Props, Status }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ InitialStateAsEvents, MemberJoined, MemberUp }
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination

import scala.concurrent.duration.{ Duration, FiniteDuration }

object ConstructrMachine {

  implicit class DurationOps(val duration: Duration) extends AnyVal {

    def toFinite: FiniteDuration =
      duration match {
        case Duration(n, unit) => Duration(n, unit)
        case _                 => throw new IllegalStateException("Infinite duration!")
      }
  }

  sealed trait State
  final object State {
    case object BeforeGettingNodes extends State
    case object GettingNodes       extends State
    case object Locking            extends State
    case object Joining            extends State
    case object AddingSelf         extends State
    case object RefreshScheduled   extends State
    case object Refreshing         extends State
    case object RetryScheduled     extends State
  }

  final case class Data(nodes: Set[Address], retryState: State, nrOfRetriesLeft: Int)

  final case class StateTimeoutException(state: State)
      extends RuntimeException(s"State timeout triggered in state $state!")

  final val Name = "constructr-machine"

  def props(
      selfNode: Address,
      coordination: Coordination,
      coordinationTimeout: FiniteDuration,
      coordinationRetries: Int,
      retryDelay: FiniteDuration,
      refreshInterval: FiniteDuration,
      ttlFactor: Double,
      maxNrOfSeedNodes: Int,
      joinTimeout: FiniteDuration,
      abortOnJoinTimeout: Boolean,
      ignoreRefreshFailures: Boolean
  ): Props =
    Props(
      new ConstructrMachine(
        selfNode,
        coordination,
        coordinationTimeout,
        coordinationRetries,
        retryDelay,
        refreshInterval,
        ttlFactor,
        maxNrOfSeedNodes,
        joinTimeout,
        abortOnJoinTimeout,
        ignoreRefreshFailures
      )
    )
}

final class ConstructrMachine(
    val selfNode: Address,
    coordination: Coordination,
    coordinationTimeout: FiniteDuration,
    nrOfRetries: Int,
    retryDelay: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double,
    maxNrOfSeedNodes: Int,
    joinTimeout: FiniteDuration,
    abortOnJoinTimeout: Boolean,
    ignoreRefreshFailures: Boolean
) extends FSM[ConstructrMachine.State, ConstructrMachine.Data] {
  import ConstructrMachine._
  import context.dispatcher

  require(maxNrOfSeedNodes > 0,
          s"max-nr-of-seed-nodes must be positive, but was $maxNrOfSeedNodes!")
  require(
    ttlFactor >= minTtlFactor,
    s"ttl-factor must be greater or equal 1 + ((coordination-timeout * (1 + nr-of-retries) + retry-delay * nr-of-retries)/ refresh-interval), i.e. $minTtlFactor, but was $ttlFactor!"
  )

  private val cluster = Cluster(context.system)

  startWith(State.GettingNodes, Data(Set.empty, State.GettingNodes, nrOfRetries))

  // Before getting nodes

  when(State.BeforeGettingNodes, retryDelay) {
    case Event(StateTimeout, _) =>
      goto(State.GettingNodes).using(Data(Set.empty, State.GettingNodes, nrOfRetries))
  }

  // Getting nodes

  onTransition {
    case _ -> State.GettingNodes =>
      log.debug("Transitioning to GettingNodes")
      coordination.getNodes().map { nodes =>
        if (nodes.contains(selfNode))
          log.warning(
            s"Selfnode received in list of nodes $nodes. Will filter to prevent forming an island."
          )

        nodes.filterNot(_ == selfNode)
      } pipeTo self
  }

  when(State.GettingNodes, coordinationTimeout) {
    case Event(nodes: Set[Address] @unchecked, _) if nodes.isEmpty =>
      log.debug("Received empty nodes, going to Locking")
      goto(State.Locking)
        .using(stateData.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(nodes: Set[Address] @unchecked, _) =>
      log.debug(s"Received nodes $nodes, going to Joining")
      goto(State.Joining)
        .using(stateData.copy(nodes = nodes, nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to GettingNodes: $cause")
      retry(State.GettingNodes)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to GettingNodes")
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
      goto(State.Joining).using(
        stateData.copy(nodes = Set(selfNode), nrOfRetriesLeft = nrOfRetries)
      )

    case Event(false, _) =>
      log.warning("Couldn't acquire lock, going to GettingNodes")
      goto(State.BeforeGettingNodes)

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to Locking: $cause")
      retry(State.Locking)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to Locking")
      retry(State.Locking)
  }

  // Joining

  onTransition {
    case _ -> State.Joining =>
      log.debug("Transitioning to Joining")
      cluster.joinSeedNodes(seedNodes(nextStateData.nodes)) // An existing seed node process would be stopped
      Cluster(context.system).subscribe(self,
                                        InitialStateAsEvents,
                                        classOf[MemberJoined],
                                        classOf[MemberUp])

  }

  when(State.Joining, joinTimeout) {
    case Event(MemberJoined(member), _) if member.address == selfNode =>
      goto(State.AddingSelf)

    case Event(MemberUp(member), _) if member.address == selfNode =>
      goto(State.AddingSelf)

    case Event(StateTimeout, _) =>
      if (abortOnJoinTimeout)
        stop(FSM.Failure("Timeout in Joining!"))
      else
        goto(State.GettingNodes).using(Data(Set.empty, State.GettingNodes, nrOfRetries))
  }

  onTransition {
    case State.Joining -> _ =>
      log.debug("Transitioning out of Joining")
      cluster.unsubscribe(self)
  }

  private def seedNodes(nodes: Set[Address]) =
    nodes.take(maxNrOfSeedNodes).toVector

  // AddingSelf

  onTransition {
    case _ -> State.AddingSelf =>
      log.debug("Transitioning to AddingSelf")
      coordination
        .addSelf(selfNode, addingSelfOrRefreshingTtl.toFinite)
        .pipeTo(self)
  }

  when(State.AddingSelf, coordinationTimeout) {
    case Event(Done, data) =>
      log.debug("Successfully added self, going to RefreshScheduled")
      goto(State.RefreshScheduled)
        .using(data.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to AddingSelf: $cause")
      retry(State.AddingSelf)

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to AddingSelf")
      retry(State.AddingSelf)
  }

  // RefreshScheduled

  onTransition {
    case _ -> State.RefreshScheduled =>
      log.debug("Transitioning to RefreshScheduled")
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
      coordination
        .refresh(selfNode, addingSelfOrRefreshingTtl.toFinite)
        .pipeTo(self)
  }

  when(State.Refreshing, coordinationTimeout) {
    case Event(Done, _) =>
      log.debug("Successfully refreshed, going to RefreshScheduled")
      goto(State.RefreshScheduled)
        .using(stateData.copy(nrOfRetriesLeft = nrOfRetries))

    case Event(Status.Failure(cause), _) =>
      log.warning(s"Failure in $stateName, going to Refreshing: $cause")
      retryRefreshing()

    case Event(StateTimeout, _) =>
      log.warning(s"Timeout in $stateName, going to Refreshing")
      retryRefreshing()
  }

  // RetryScheduled

  onTransition {
    case state -> State.RetryScheduled =>
      log.debug(s"Transitioning from $state to RetryScheduled")
  }

  when(State.RetryScheduled, retryDelay) {
    case Event(StateTimeout, Data(_, retryState, _)) =>
      log.debug(s"Waited for $retryDelay, going to $retryState")
      goto(retryState)
  }

  // Unhandled events

  whenUnhandled {
    case Event(MemberJoined(member), _) if member.address == selfNode && isPreJoining =>
      goto(State.AddingSelf).using(stateData.copy(nrOfRetriesLeft = nrOfRetries))
    case Event(MemberUp(member), _) if member.address == selfNode && isPreJoining =>
      goto(State.AddingSelf).using(stateData.copy(nrOfRetriesLeft = nrOfRetries))
    // Unsubscribe might be late
    case Event(MemberJoined(_) | MemberUp(_), _) => stay()
  }

  // Initialization

  initialize()

  // Helpers

  private def retry(retryState: ConstructrMachine.State) =
    if (stateData.nrOfRetriesLeft <= 0)
      stop(FSM.Failure(s"Number of retries exhausted in $stateName!"))
    else
      goto(State.RetryScheduled).using(
        stateData.copy(retryState = retryState, nrOfRetriesLeft = stateData.nrOfRetriesLeft - 1)
      )

  private def maxCoordinationTimeout =
    coordinationTimeout * (1 + nrOfRetries) + retryDelay * nrOfRetries

  private def minTtlFactor = 1 + maxCoordinationTimeout / refreshInterval

  private def addingSelfOrRefreshingTtl = refreshInterval * ttlFactor

  private def retryRefreshing() =
    if (ignoreRefreshFailures)
      goto(State.RetryScheduled).using(
        stateData.copy(retryState = State.Refreshing, nrOfRetriesLeft = nrOfRetries)
      )
    else
      retry(State.Refreshing)

  private def isPreJoining: Boolean =
    stateName match {
      case State.BeforeGettingNodes | State.GettingNodes | State.Locking => true
      case _                                                             => false
    }
}
