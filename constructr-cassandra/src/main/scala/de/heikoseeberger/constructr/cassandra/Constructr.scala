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

package de.heikoseeberger.constructr.cassandra

import akka.actor.{ Actor, ActorLogging, ActorRef, FSM, Props, SupervisorStrategy, Terminated }
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination
import de.heikoseeberger.constructr.machine.ConstructrMachine
import de.heikoseeberger.constructr.machine.ConstructrMachine.State
import java.net.InetAddress
import scala.concurrent.duration.Duration

object Constructr {

  final val Name = "constructr"

  case object GetNodes
  final case class Nodes(value: Set[InetAddress])

  def props: Props = Props(new Constructr)

  private def intoJoiningHandler(machine: ConstructrMachine[InetAddress]) = {
    import machine._
    self ! Constructr.Nodes(seedNodes(nextStateData.nodes))
  }

  private def joiningFunction(machine: ConstructrMachine[InetAddress]): ConstructrMachine.StateFunction[InetAddress] = {
    import machine._
    { case FSM.Event(FSM.StateTimeout, _) => goto(State.AddingSelf) }
  }

  private def outOfJoiningHandler(machine: ConstructrMachine[InetAddress]) = ()
}

final class Constructr private extends Actor with ActorLogging with ActorSettings {
  import Constructr._

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  private val machine = context.watch(createConstructrMachine())

  override def receive = waitingForNodes(Set.empty)

  private def waitingForNodes(requesters: Set[ActorRef]): Receive = receiveTerminated.orElse {
    case GetNodes =>
      context.become(waitingForNodes(requesters + sender()))

    case nodes: Nodes =>
      requesters.foreach(_ ! nodes)
      context.become(nodesReceived(nodes))
  }

  private def nodesReceived(nodes: Nodes): Receive = receiveTerminated.orElse {
    case GetNodes => sender() ! nodes
  }

  private def receiveTerminated: Receive = {
    case Terminated(`machine`) =>
      log.error("Terminating the system, because constructr-machine has terminated!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    val coordination = {
      import settings.coordination._
      val connection = Http()(context.system).outgoingConnection(host, port)
      Coordination("cassandra", settings.clusterName, context.system.settings.config)(connection, ActorMaterializer())
    }
    context.actorOf(
      ConstructrMachine.props(
        settings.selfNode,
        coordination,
        settings.coordinationTimeout,
        settings.nrOfRetries,
        settings.retryDelay,
        settings.refreshInterval,
        settings.ttlFactor,
        settings.maxNrOfSeedNodes,
        Duration.Zero,
        intoJoiningHandler,
        joiningFunction,
        outOfJoiningHandler
      ),
      ConstructrMachine.Name
    )
  }
}
