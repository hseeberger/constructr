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

import akka.actor.{ SupervisorStrategy, Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ InitialStateAsEvents, MemberExited, MemberLeft, MemberRemoved }
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination

object Constructr {

  final val Name = "constructr"

  def props: Props = Props(new Constructr)
}

final class Constructr private extends Actor with ActorLogging with ActorSettings {

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  if (Cluster(context.system).settings.SeedNodes.isEmpty) {
    log.info("Creating constructr-machine, because no seed-nodes defined")
    Cluster(context.system).subscribe(self, InitialStateAsEvents, classOf[MemberLeft], classOf[MemberExited], classOf[MemberRemoved])
    context.become(active(context.watch(createConstructrMachine())))
  } else {
    log.info("Stopping self, because seed-nodes defined")
    context.stop(self)
  }

  override def receive = Actor.emptyBehavior

  private def active(machine: ActorRef): Receive = {
    case Terminated(`machine`) =>
      log.error("Terminating the system, because constructr-machine has terminated!")
      context.system.terminate()

    case MemberRemoved(member, _) if member.address == Cluster(context.system).selfAddress =>
      log.error("Terminating the system, because member has been removed!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    val coordination = {
      import settings.coordination._
      val connection = Http()(context.system).outgoingConnection(host, port)
      Coordination("akka", context.system.name, context.system.settings.config)(connection, ActorMaterializer())
    }
    context.actorOf(
      AkkaConstructrMachine.props(
        Cluster(context.system).selfAddress,
        coordination,
        settings.coordinationTimeout,
        settings.nrOfRetries,
        settings.retryDelay,
        settings.refreshInterval,
        settings.ttlFactor,
        settings.maxNrOfSeedNodes,
        settings.joinTimeout
      ),
      AkkaConstructrMachine.Name
    )
  }
}
