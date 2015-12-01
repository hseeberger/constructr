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

import akka.actor.{ Address, Actor, ActorLogging, Props, SupervisorStrategy, Terminated }
import akka.cluster.{ ClusterEvent, Cluster }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination
import de.heikoseeberger.constructr.machine.ConstructrMachine

object Constructr {
  import ClusterEvent._

  final val Name = "constructr-akka"

  def props(strategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy): Props = Props(new Constructr(strategy))

  private def intoJoiningHandler[B <: Coordination.Backend](machine: ConstructrMachine[Address, B]) = {
    Cluster(machine.context.system).joinSeedNodes(machine.nextStateData.nodes) // An existing seed node process would be stopped
    Cluster(machine.context.system).subscribe(machine.self, InitialStateAsEvents, classOf[MemberJoined], classOf[MemberUp])
  }

  private def joiningFunction[B <: Coordination.Backend](machine: ConstructrMachine[Address, B]): ConstructrMachine.StateFunction[Address, B] = {
    case machine.Event(MemberJoined(member), _) if member.address == machine.selfAddress => machine.goto(ConstructrMachine.State.AddingSelf)
    case machine.Event(MemberJoined(member), _)                                          => machine.stay()
    case machine.Event(MemberUp(member), _) if member.address == machine.selfAddress     => machine.goto(ConstructrMachine.State.AddingSelf)
    case machine.Event(MemberUp(member), _)                                              => machine.stay()
  }

  private def outOfJoiningHandler[B <: Coordination.Backend](machine: ConstructrMachine[Address, B]) =
    Cluster(machine.context.system).unsubscribe(machine.self)
}

final class Constructr private (override val supervisorStrategy: SupervisorStrategy)
    extends Actor with ActorLogging with ActorSettings {
  import Constructr._

  private val machine = context.watch(createConstructrMachine())

  override def receive = {
    case Terminated(`machine`) =>
      log.error("Terminating the system, because constructr-machine has terminated!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    implicit val mat = ActorMaterializer()
    val send = Http()(context.system).singleRequest(_: HttpRequest)
    val coordination = Coordination(settings.coordination.backend)(
      "akka", context.system.name, settings.coordination.host, Integer.valueOf(settings.coordination.port), send
    )
    context.actorOf(
      ConstructrMachine.props(
        Cluster(context.system).selfAddress,
        coordination,
        settings.coordinationTimeout,
        settings.coordinationRetries,
        settings.retryGetNodesDelay,
        settings.refreshInterval,
        settings.ttlFactor,
        Some(settings.joinTimeout),
        intoJoiningHandler,
        joiningFunction,
        outOfJoiningHandler
      ),
      ConstructrMachine.Name
    )
  }
}
