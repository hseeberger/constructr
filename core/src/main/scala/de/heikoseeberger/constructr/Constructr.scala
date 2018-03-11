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

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, Terminated }
import akka.cluster.{ Cluster, Member }
import akka.cluster.ClusterEvent.{ InitialStateAsEvents, MemberExited, MemberLeft, MemberRemoved }
import akka.cluster.MemberStatus.Up
import de.heikoseeberger.constructr.coordination.Coordination
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

object Constructr {

  final val Name = "constructr"

  def props: Props =
    Props(new Constructr)
}

final class Constructr private extends Actor with ActorLogging {

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  private val cluster = Cluster(context.system)

  if (cluster.settings.SeedNodes.isEmpty) {
    log.info("Creating constructr-machine, because no seed-nodes defined")
    cluster.subscribe(self,
                      InitialStateAsEvents,
                      classOf[MemberLeft],
                      classOf[MemberExited],
                      classOf[MemberRemoved])
    context.become(active(context.watch(createConstructrMachine())))
  } else {
    log.info("Stopping self, because seed-nodes defined")
    context.stop(self)
  }

  override def receive = Actor.emptyBehavior

  private def active(machine: ActorRef): Receive = {
    case Terminated(`machine`) =>
      val selfAddress = cluster.selfAddress
      def isSelfAndUp(member: Member) =
        member.address == selfAddress && member.status == Up
      if (cluster.state.members.exists(isSelfAndUp)) {
        log.error("Leaving, because constructr-machine terminated!")
        cluster.leave(selfAddress)
      } else {
        log.error("Terminating system, because constructr-machine terminated!")
        context.system.terminate()
      }

    case MemberRemoved(member, _) if member.address == cluster.selfAddress =>
      log.error("Terminating system, because member has been removed!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    val config = context.system.settings.config
    def getDuration(key: String) =
      FiniteDuration(config.getDuration(key).toNanos, NANOSECONDS)

    val coordinationTimeout   = getDuration("constructr.coordination-timeout")
    val nrOfRetries           = config.getInt("constructr.nr-of-retries")
    val retryDelay            = getDuration("constructr.retry-delay")
    val refreshInterval       = getDuration("constructr.refresh-interval")
    val ttlFactor             = config.getDouble("constructr.ttl-factor")
    val maxNrOfSeedNodes      = config.getInt("constructr.max-nr-of-seed-nodes")
    val joinTimeout           = getDuration("constructr.join-timeout")
    val abortOnJoinTimeout    = config.getBoolean("constructr.abort-on-join-timeout")
    val ignoreRefreshFailures = config.getBoolean("constructr.ignore-refresh-failures")

    context.actorOf(
      ConstructrMachine.props(
        cluster.selfAddress,
        Coordination(context.system.name, context.system),
        coordinationTimeout,
        nrOfRetries,
        retryDelay,
        refreshInterval,
        ttlFactor,
        if (maxNrOfSeedNodes <= 0) Int.MaxValue else maxNrOfSeedNodes,
        joinTimeout,
        abortOnJoinTimeout,
        ignoreRefreshFailures
      ),
      ConstructrMachine.Name
    )
  }
}
