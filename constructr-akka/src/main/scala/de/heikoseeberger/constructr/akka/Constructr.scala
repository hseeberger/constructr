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

  final val Name = "constructr-akka"

  def props(strategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy): Props = Props(new Constructr(strategy))

  private def intoJoiningHandler(machine: ConstructrMachine[Address]): ConstructrMachine.TransitionHandler[Address] = {
    case (_, ConstructrMachine.State.Joining) =>
      Cluster(machine.context.system).joinSeedNodes(machine.nextStateData) // An existing seed node process would be stopped
      Cluster(machine.context.system).subscribe(machine.self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.MemberUp])
  }

  def joiningFunction(machine: ConstructrMachine[Address]): ConstructrMachine.StateFunction[Address] = {
    case machine.Event(ClusterEvent.MemberUp(member), _) if member.address == machine.selfAddress =>
      machine.goto(ConstructrMachine.State.AddingSelf)
  }

  private def outOfJoiningHandler(machine: ConstructrMachine[Address]): ConstructrMachine.TransitionHandler[Address] = {
    case (ConstructrMachine.State.Joining, _) => Cluster(machine.context.system).unsubscribe(machine.self)
  }

}

final class Constructr private (override val supervisorStrategy: SupervisorStrategy)
    extends Actor with ActorLogging with ActorSettings {
  import Constructr._

  private val machine = context.watch(createConstructrMachine())

  override def receive = {
    case Terminated(`machine`) =>
      log.error("Terminating the system, because constructr-machine has terminated!")
      context.system.shutdown()
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
