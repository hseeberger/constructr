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
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ InitialStateAsEvents, MemberExited, MemberLeft, MemberRemoved }
import de.heikoseeberger.constructr.coordination.Coordination

import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }
import scala.util.control.NonFatal

private[constructr] object Constructr {

  final val Name = "constructr"

  def props: Props = Props(new Constructr)

  case class RegisterFailureHandler(callback: Runnable)
}

private[constructr] class Constructr private
    extends Actor
    with ActorLogging
    with ConstructrFailureListening {

  import Constructr.RegisterFailureHandler

  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  private[this] val cluster                         = Cluster(context.system)
  private[this] var machineOption: Option[ActorRef] = None
  private[this] var failureListeners                = Set.empty[ActorRef]

  if (cluster.settings.SeedNodes.isEmpty) {
    log.info("Creating constructr-machine, because no seed-nodes are defined")
    cluster.subscribe(self,
                      InitialStateAsEvents,
                      classOf[MemberLeft],
                      classOf[MemberExited],
                      classOf[MemberRemoved])
    machineOption = Some(context.watch(createConstructrMachine()))
  } else {
    log.info("Stopping ConstructR, because seed-nodes are defined in configuration.")
    context.stop(self)
  }

  override def receive: Receive = {
    case Terminated(machine) if machineOption.contains(machine) =>
      machineOption = None

    case Terminated(failureListener) if failureListeners.contains(failureListener) =>
      failureListeners -= failureListener

    case RegisterFailureHandler(callback) if machineOption.nonEmpty =>
      failureListeners += context.actorOf(
        Props(classOf[ConstructrFailureListener], machineOption.get, callback)
      )

    case RegisterFailureHandler(callback) if machineOption.isEmpty =>
      executeFailureHandler(callback)

    case MemberRemoved(member, _) if member.address == cluster.selfAddress =>
      log.warning("Stopping ConstructR because cluster member has been removed!")
      context.stop(self)
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
        ignoreRefreshFailures
      ),
      ConstructrMachine.Name
    )
  }
}

private[constructr] class ConstructrFailureListener(machine: ActorRef, callback: Runnable)
    extends Actor
    with ActorLogging
    with ConstructrFailureListening {

  override def preStart(): Unit = {
    super.preStart()
    context.watch(machine)
  }

  override def postStop(): Unit = {
    context.unwatch(machine)
    super.postStop()
  }

  override def receive: Receive = {
    case Terminated(`machine`) =>
      try {
        executeFailureHandler(callback)
      } finally {
        context.stop(self)
      }
  }
}

private[constructr] trait ConstructrFailureListening {
  this: Actor with ActorLogging =>

  def executeFailureHandler(callback: Runnable): Unit =
    try {
      callback.run()
    } catch {
      case NonFatal(e) =>
        log.error(e, "ConstructR failure callback failed with [{}]", e.getMessage)
    }

}
