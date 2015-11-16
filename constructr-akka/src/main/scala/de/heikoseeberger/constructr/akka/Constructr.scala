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

import akka.actor.{ Actor, ActorLogging, Props, SupervisorStrategy, Terminated }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination

object Constructr {

  final val Name = "constructr"

  def props(strategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy): Props = Props(new Constructr(strategy))
}

final class Constructr(override val supervisorStrategy: SupervisorStrategy)
    extends Actor with ActorLogging with ActorSettings {

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
        coordination,
        settings.coordinationTimeout,
        settings.retryGetNodesDelay,
        settings.joinTimeout,
        settings.refreshInterval,
        settings.ttlFactor
      ),
      ConstructrMachine.Name
    )
  }
}
