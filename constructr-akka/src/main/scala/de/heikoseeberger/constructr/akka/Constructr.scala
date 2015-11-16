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
import akka.stream.ActorMaterializer
import scala.concurrent.duration.{ Duration, MILLISECONDS }

object Constructr {

  final val Name = "constructr"

  def props(strategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy): Props = Props(new Constructr(strategy))
}

final class Constructr(override val supervisorStrategy: SupervisorStrategy) extends Actor with ActorLogging {

  private val machine = context.watch(createConstructrMachine())

  override def receive = {
    case Terminated(`machine`) =>
      log.error("Terminating the system, because constructr-machine has terminated!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    implicit val mat = ActorMaterializer()
    val config = context.system.settings.config.getConfig("constructr")
    def getDuration(key: String) = Duration(config.getDuration(key).toMillis, MILLISECONDS)
    context.actorOf(
      ConstructrMachine.props(
        Http()(context.system).singleRequest(_),
        config.getString("etcd.host"),
        config.getInt("etcd.port"),
        getDuration("etcd.timeout"),
        getDuration("retry-get-nodes-delay"),
        getDuration("join-timeout"),
        getDuration("refresh-interval"),
        config.getDouble("ttl-factor")
      ),
      ConstructrMachine.Name
    )
  }
}
