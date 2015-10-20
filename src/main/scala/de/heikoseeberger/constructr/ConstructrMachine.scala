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

import akka.actor.{ ActorLogging, Address, AddressFromURIString, FSM, Props, Status }
import akka.cluster.{ Cluster, ClusterEvent }
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes.{ Created, NotFound, OK, PreconditionFailed }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, ResponseEntity, StatusCode, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationInt

object ConstructrMachine {

  type Send = HttpRequest => Future[HttpResponse]

  sealed trait State
  object State {
    case object GettingNodes extends State
    case object Locking extends State
    case object BeforeGettingNodes extends State
    case object Joining extends State
    case object AddingSelf extends State
    case object Refreshing extends State
  }

  sealed abstract class ConstructrMachineFailure(message: String) extends RuntimeException(message)
  object ConstructrMachineFailure {
    case class UnexpectedStatusCode(transition: (State, State), statusCode: StatusCode)
      extends ConstructrMachineFailure(s"Unexpected status code $statusCode on transition $transition!")
    case class StateTimeout(state: State) extends ConstructrMachineFailure(s"State timeout triggered in state $state!")
  }

  private case object Locked
  private case object LockingFailed
  private case object SelfAdded
  private case object Refresh
  private case object Refreshed

  final val Name = "constructr-machine"

  private final val ConstructrKey = "constructr"

  private final val Nodes = "nodes"

  def props(
    send: ConstructrMachine.Send,
    etcdHost: String,
    etcdPort: Int,
    etcdTimeout: FiniteDuration,
    retryGetNodesDelay: FiniteDuration,
    joinTimeout: FiniteDuration,
    refreshInterval: FiniteDuration,
    ttlFactor: Double
  )(implicit mat: Materializer): Props =
    Props(new ConstructrMachine(send, etcdHost, etcdPort, etcdTimeout, retryGetNodesDelay, joinTimeout, refreshInterval, ttlFactor))

  private def decodeAddress(s: String) = AddressFromURIString(new String(Base64.getUrlDecoder.decode(s), UTF_8))

  private def encodeAddress(address: Address) = Base64.getUrlEncoder.encodeToString(address.toString.getBytes(UTF_8))

  private def ignore[A](entity: ResponseEntity)(a: A)(implicit ec: ExecutionContext, mat: Materializer) =
    entity.dataBytes.runWith(Sink.ignore).map(_ => a)
}

final class ConstructrMachine(
  send: ConstructrMachine.Send,
  etcdHost: String,
  etcdPort: Int,
  etcdTimeout: FiniteDuration,
  retryGetNodesDelay: FiniteDuration,
  joinTimeout: FiniteDuration,
  refreshInterval: FiniteDuration,
  ttlFactor: Double
)(implicit mat: Materializer)
    extends FSM[ConstructrMachine.State, List[Address]] with ActorLogging {
  import ConstructrMachine._
  import RequestBuilding._
  import context.dispatcher

  require(ttlFactor > 1.0, "ttlFactor must be greater than one!")

  private val cluster = Cluster(context.system)

  private val systemName = context.system.name

  private val baseUri = Uri(s"http://$etcdHost:$etcdPort/v2/keys/$ConstructrKey/$systemName")

  private val nodesUri = baseUri.withPath(baseUri.path / Nodes)

  private val addOrUpdateUri = {
    val ttl = (refreshInterval * ttlFactor).toSeconds + 1
    nodesUri
      .withPath(nodesUri.path / encodeAddress(cluster.selfAddress))
      .withQuery("ttl" -> ttl.toString, "value" -> cluster.selfAddress.toString)
  }

  startWith(State.GettingNodes, Nil)

  // Getting nodes

  onTransition {
    case transition @ _ -> State.GettingNodes =>
      log.debug(s"Transitioning to ${State.GettingNodes}")
      def getNodes() = send(Get(nodesUri)).flatMap {
        case HttpResponse(OK, _, entity, _)       => unmarshalNodes(entity)
        case HttpResponse(NotFound, _, entity, _) => ignore(entity)(Nil)
        case HttpResponse(other, _, entity, _)    => ignore(entity)(ConstructrMachineFailure.UnexpectedStatusCode(transition, other))
      }
      getNodes().pipeTo(self)
  }

  when(State.GettingNodes, etcdTimeout) {
    case Event(Nil, _)                             => goto(State.Locking)
    case Event(nodes: List[Address] @unchecked, _) => goto(State.Joining).using(nodes)
  }

  private def unmarshalNodes(entity: ResponseEntity) = {
    def toNodes(s: String) = {
      import rapture.json._
      import rapture.json.jsonBackends.spray._
      def toAddress(node: Json) = decodeAddress(node.key.as[String].substring(s"/$ConstructrKey/$systemName/$Nodes/".length))
      Json.parse(s).node match {
        case json"""{ "nodes": $nodes }""" => nodes.as[List[Json]].map(toAddress)
        case _                             => Nil
      }
    }
    Unmarshal(entity).to[String].map(toNodes)
  }

  // Locking

  onTransition {
    case transition @ _ -> State.Locking =>
      log.debug(s"Transitioning to ${State.Locking}")
      val ttl = (2 * etcdTimeout + joinTimeout).toSeconds + 1 // Lock must be kept until self added
      val uri = baseUri
        .withPath(baseUri.path / "lock")
        .withQuery("prevExist" -> "false", "ttl" -> ttl.toString)
      def lock() = send(Put(uri)).flatMap {
        case HttpResponse(Created, _, entity, _)            => ignore(entity)(Locked)
        case HttpResponse(PreconditionFailed, _, entity, _) => ignore(entity)(LockingFailed)
        case HttpResponse(other, _, entity, _)              => ignore(entity)(throw ConstructrMachineFailure.UnexpectedStatusCode(transition, other))
      }
      lock().pipeTo(self)
  }

  when(State.Locking, etcdTimeout) {
    case Event(Locked, _)        => goto(State.Joining).using(List(cluster.selfAddress))
    case Event(LockingFailed, _) => goto(State.BeforeGettingNodes)
  }

  // BeforeGettingNodes

  when(State.BeforeGettingNodes, retryGetNodesDelay) {
    case Event(StateTimeout, _) => goto(State.GettingNodes)
  }

  // Joining

  onTransition {
    case _ -> State.Joining =>
      log.debug(s"Transitioning to ${State.Joining}")
      cluster.joinSeedNodes(nextStateData) // An existing seed node process would be stopped
      cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.MemberUp])
  }

  when(State.Joining, joinTimeout) {
    case Event(ClusterEvent.MemberUp(member), _) if member.address == cluster.selfAddress => goto(State.AddingSelf)
    case Event(ClusterEvent.MemberUp(member), _)                                          => stay() // Avoid warnings
  }

  onTransition {
    case State.Joining -> _ => cluster.unsubscribe(self)
  }

  // AddingSelf

  onTransition {
    case transition @ _ -> State.AddingSelf =>
      log.debug(s"Transitioning to ${State.AddingSelf}")
      def addSelf() = send(Put(addOrUpdateUri)).flatMap {
        case HttpResponse(Created, _, entity, _) => ignore(entity)(SelfAdded)
        case HttpResponse(other, _, entity, _)   => ignore(entity)(throw ConstructrMachineFailure.UnexpectedStatusCode(transition, other))
      }
      addSelf().pipeTo(self)
  }

  when(State.AddingSelf, etcdTimeout) {
    case Event(SelfAdded, _) => goto(State.Refreshing)
  }

  // Refreshing

  onTransition {
    case _ -> State.Refreshing =>
      log.debug(s"Transitioning to ${State.Refreshing}")
      scheduleRefresh()
  }

  when(State.Refreshing, refreshInterval + 1.second + etcdTimeout) { // Allow for some inaccuracy here ... 1 sec should be more than enough
    case Event(Refresh, _) =>
      onRefresh()
      stay()
    case Event(Refreshed, _) =>
      scheduleRefresh()
      stay()
  }

  private def scheduleRefresh() = setTimer("refresh", Refresh, refreshInterval)

  private def onRefresh() = {
    def refresh() = send(Put(addOrUpdateUri)).flatMap {
      case HttpResponse(OK, _, entity, _)    => ignore(entity)(Refreshed)
      case HttpResponse(other, _, entity, _) => ignore(entity)(throw ConstructrMachineFailure.UnexpectedStatusCode(State.Refreshing -> State.Refreshing, other))
    }
    refresh().pipeTo(self)
  }

  // Handle failure

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error(cause, "Unexpected failure!")
      throw cause
    case Event(StateTimeout, _) =>
      log.error(s"Timeout in state $stateName!")
      throw ConstructrMachineFailure.StateTimeout(stateName)
  }

  initialize()
}
