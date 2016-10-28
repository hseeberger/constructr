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

import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  FSM,
  Props,
  SupervisorStrategy,
  Terminated
}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{
  InitialStateAsEvents,
  MemberExited,
  MemberJoined,
  MemberLeft,
  MemberRemoved,
  MemberUp
}
import akka.cluster.MemberStatus.Up
import de.heikoseeberger.constructr.coordination.Coordination

object Constructr {

  final val Name = "constructr"

  def props: Props = Props(new Constructr)

  private def intoJoiningHandler(machine: ConstructrMachine) = {
    import machine._
    Cluster(context.system).joinSeedNodes(
      seedNodes(nextStateData.nodes).toVector) // An existing seed node process would be stopped
    Cluster(context.system).subscribe(self,
                                      InitialStateAsEvents,
                                      classOf[MemberJoined],
                                      classOf[MemberUp])
  }

  private def joiningFunction(
      machine: ConstructrMachine): ConstructrMachine.StateFunction = {
    import ConstructrMachine._
    import machine._
    {
      case FSM.Event(MemberJoined(member), _) if member.address == selfNode =>
        goto(State.AddingSelf)
      case FSM.Event(MemberJoined(member), _) => stay()
      case FSM.Event(MemberUp(member), _) if member.address == selfNode =>
        goto(State.AddingSelf)
      case FSM.Event(MemberUp(member), _) => stay()
      case FSM.Event(StateTimeout, _) =>
        stop(FSM.Failure("Timeout in Joining!"))
    }
  }

  private def outOfJoiningHandler(machine: ConstructrMachine) = {
    import machine._
    Cluster(context.system).unsubscribe(self)
  }
}

final class Constructr private
    extends Actor
    with ActorLogging
    with ActorSettings {
  import Constructr._

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  if (Cluster(context.system).settings.SeedNodes.isEmpty) {
    log.info("Creating constructr-machine, because no seed-nodes defined")
    Cluster(context.system).subscribe(self,
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
      val cluster     = Cluster(context.system)
      val selfAddress = cluster.selfAddress
      if (cluster.state.members.exists(member =>
            member.address == selfAddress && member.status == Up)) {
        log.error(
          "Leaving the cluster, because constructr-machine has terminated!")
        cluster.leave(selfAddress)
      } else {
        log.error(
          "Terminating the system, because constructr-machine has terminated!")
        context.system.terminate()
      }

    case MemberRemoved(member, _)
        if member.address == Cluster(context.system).selfAddress =>
      log.error("Terminating the system, because member has been removed!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    context.actorOf(
      ConstructrMachine.props(
        Cluster(context.system).selfAddress,
        Coordination("akka", context.system.name, context.system),
        settings.coordinationTimeout,
        settings.nrOfRetries,
        settings.retryDelay,
        settings.refreshInterval,
        settings.ttlFactor,
        settings.maxNrOfSeedNodes,
        settings.joinTimeout,
        intoJoiningHandler,
        joiningFunction,
        outOfJoiningHandler
      ),
      ConstructrMachine.Name
    )
  }
}
