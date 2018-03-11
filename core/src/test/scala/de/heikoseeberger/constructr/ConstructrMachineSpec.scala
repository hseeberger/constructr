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

import akka.Done
import akka.actor.{ ActorSystem, Address, FSM, Props }
import akka.cluster.Cluster
import akka.pattern.{ after => delayed }
import akka.stream.ActorMaterializer
import akka.testkit.{ TestDuration, TestProbe }
import de.heikoseeberger.constructr.coordination.Coordination
import org.mockito.Mockito
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.concurrent.duration.{ Duration, DurationInt }
import scala.concurrent.{ Await, Future }

final class ConstructrMachineSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  import ConstructrMachine._
  import Mockito._

  private implicit val system = ActorSystem()
  private implicit val mat    = ActorMaterializer()
  import system.dispatcher

  private val address = Cluster(system).selfAddress

  "ConstructrMachine" should {
    "retry the given number of retries and then fail" in {
      val coordination = mock(classOf[Coordination])
      when(coordination.getNodes()).thenReturn(
        // delay first getNodes call for a bit, so as not to transition out of GettingNodes state too fast
        delayed(50.millis.dilated, system.scheduler)(boom()),
        delayed(1.hour.dilated, system.scheduler)(noNodes())
      )

      val monitor = TestProbe()
      val machine = system.actorOf(
        Props(
          new ConstructrMachine(
            selfNode = address,
            coordination = coordination,
            coordinationTimeout = 100.millis.dilated,
            nrOfRetries = 1,
            retryDelay = 100.millis.dilated,
            refreshInterval = 1.second.dilated,
            ttlFactor = 1.5,
            maxNrOfSeedNodes = 3,
            joinTimeout = 100.millis.dilated,
            abortOnJoinTimeout = false,
            ignoreRefreshFailures = false
          )
        )
      )
      machine ! FSM.SubscribeTransitionCallBack(monitor.ref)
      monitor.watch(machine)

      monitor.expectMsgPF(hint = "Current state GettingNodes") {
        case FSM.CurrentState(_, State.GettingNodes) => ()
      }
      monitor.expectMsgPF(hint = "GettingNodes -> RetryScheduled") {
        case FSM.Transition(_, State.GettingNodes, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> GettingNodes") {
        case FSM.Transition(_, State.RetryScheduled, State.GettingNodes) => ()
      }

      monitor.expectTerminated(machine)
    }

    "correctly work down the happy path (including retries)" in {
      val coordination = mock(classOf[Coordination])
      when(coordination.getNodes()).thenReturn(
        delayed(1.hour.dilated, system.scheduler)(noNodes()),
        boom(),
        noNodes(),
        noNodes()
      )
      when(coordination.lock(address, 1650.millis.dilated)).thenReturn(
        delayed(1.hour.dilated, system.scheduler)(boom()),
        boom(),
        Future.successful(false),
        Future.successful(true)
      )
      when(coordination.addSelf(address, 1500.millis.dilated)).thenReturn(
        delayed(1.hour.dilated, system.scheduler)(boom()),
        boom(),
        Future.successful(Done)
      )
      when(coordination.refresh(address, 1500.millis.dilated)).thenReturn(
        delayed(1.hour.dilated, system.scheduler)(boom()),
        boom(),
        Future.successful(Done)
      )

      val monitor = TestProbe()
      val machine = system.actorOf(
        Props(
          new ConstructrMachine(
            selfNode = address,
            coordination = coordination,
            coordinationTimeout = 100.millis.dilated,
            nrOfRetries = 2,
            retryDelay = 100.millis.dilated,
            refreshInterval = 1.second.dilated,
            ttlFactor = 1.5,
            maxNrOfSeedNodes = 3,
            joinTimeout = 100.millis.dilated,
            abortOnJoinTimeout = false,
            ignoreRefreshFailures = false
          )
        )
      )
      machine ! FSM.SubscribeTransitionCallBack(monitor.ref)

      monitor.expectMsgPF(hint = "Current state GettingNodes") {
        case FSM.CurrentState(_, State.GettingNodes) => ()
      }

      monitor.expectMsgPF(hint = "GettingNodes -> RetryScheduled") {
        case FSM.Transition(_, State.GettingNodes, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> GettingNodes") {
        case FSM.Transition(_, State.RetryScheduled, State.GettingNodes) => ()
      }
      monitor.expectMsgPF(hint = "GettingNodes -> RetryScheduled") {
        case FSM.Transition(_, State.GettingNodes, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> GettingNodes") {
        case FSM.Transition(_, State.RetryScheduled, State.GettingNodes) => ()
      }
      monitor.expectMsgPF(hint = "GettingNodes -> Locking") {
        case FSM.Transition(_, State.GettingNodes, State.Locking) => ()
      }

      monitor.expectMsgPF(hint = "Locking -> RetryScheduled") {
        case FSM.Transition(_, State.Locking, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> Locking") {
        case FSM.Transition(_, State.RetryScheduled, State.Locking) => ()
      }
      monitor.expectMsgPF(hint = "Locking -> RetryScheduled") {
        case FSM.Transition(_, State.Locking, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> Locking") {
        case FSM.Transition(_, State.RetryScheduled, State.Locking) => ()
      }
      monitor.expectMsgPF(hint = "Locking -> BeforeGettingNodes") {
        case FSM.Transition(_, State.Locking, State.BeforeGettingNodes) => ()
      }
      monitor.expectMsgPF(hint = "BeforeGettingNodes -> GettingNodes") {
        case FSM.Transition(_, State.BeforeGettingNodes, State.GettingNodes) => ()
      }

      monitor.expectMsgPF(hint = "GettingNodes -> Locking") {
        case FSM.Transition(_, State.GettingNodes, State.Locking) => ()
      }

      monitor.expectMsgPF(hint = "Locking -> Joining") {
        case FSM.Transition(_, State.Locking, State.Joining) => ()
      }

      monitor.expectMsgPF(hint = "Joining -> AddingSelf") {
        case FSM.Transition(_, State.Joining, State.AddingSelf) => ()
      }

      monitor.expectMsgPF(hint = "AddingSelf -> RetryScheduled") {
        case FSM.Transition(_, State.AddingSelf, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> AddingSelf") {
        case FSM.Transition(_, State.RetryScheduled, State.AddingSelf) => ()
      }
      monitor.expectMsgPF(hint = "AddingSelf -> RetryScheduled") {
        case FSM.Transition(_, State.AddingSelf, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> AddingSelf") {
        case FSM.Transition(_, State.RetryScheduled, State.AddingSelf) => ()
      }
      monitor.expectMsgPF(hint = "AddingSelf -> RefreshScheduled") {
        case FSM.Transition(_, State.AddingSelf, State.RefreshScheduled) => ()
      }

      monitor.expectMsgPF(hint = "RefreshScheduled -> Refreshing") {
        case FSM.Transition(_, State.RefreshScheduled, State.Refreshing) => ()
      }

      monitor.expectMsgPF(hint = "Refreshing -> RetryScheduled") {
        case FSM.Transition(_, State.Refreshing, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> Refreshing") {
        case FSM.Transition(_, State.RetryScheduled, State.Refreshing) => ()
      }
      monitor.expectMsgPF(hint = "Refreshing -> RetryScheduled") {
        case FSM.Transition(_, State.Refreshing, State.RetryScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RetryScheduled -> Refreshing") {
        case FSM.Transition(_, State.RetryScheduled, State.Refreshing) => ()
      }
      monitor.expectMsgPF(hint = "Refreshing -> RefreshScheduled") {
        case FSM.Transition(_, State.Refreshing, State.RefreshScheduled) => ()
      }
    }

    "machine won't terminate on exceeded number of retries in Refreshing (if it's specified in configuration)" in {
      val coordination = mock(classOf[Coordination])
      when(coordination.getNodes()).thenReturn(
        // delay first getNodes call for a bit, so as not to transition out of GettingNodes state too fast
        delayed(50.millis.dilated, system.scheduler)(noNodes())
      )

      when(coordination.lock(address, 1650.millis.dilated)).thenReturn(
        Future.successful(true)
      )

      when(coordination.addSelf(address, 1500.millis.dilated)).thenReturn(
        Future.successful(Done)
      )

      when(coordination.refresh(address, 1500.millis.dilated)).thenReturn(
        Future.successful(Done),
        delayed(1.hour.dilated, system.scheduler)(boom()),
        boom(),
        boom(),
        Future.successful(Done)
      )

      val nrOfRetries = 2
      val monitor     = TestProbe()
      val machine = system.actorOf(
        Props(
          new ConstructrMachine(
            selfNode = address,
            coordination = coordination,
            coordinationTimeout = 100.millis.dilated,
            nrOfRetries = nrOfRetries,
            retryDelay = 100.millis.dilated,
            refreshInterval = 1.second.dilated,
            ttlFactor = 1.5,
            maxNrOfSeedNodes = 3,
            joinTimeout = 100.millis.dilated,
            abortOnJoinTimeout = false,
            ignoreRefreshFailures = true
          )
        )
      )
      machine ! FSM.SubscribeTransitionCallBack(monitor.ref)

      monitor.expectMsgPF(hint = "Current state GettingNodes") {
        case FSM.CurrentState(_, State.GettingNodes) => ()
      }
      monitor.expectMsgPF(hint = "GettingNodes -> Locking") {
        case FSM.Transition(_, State.GettingNodes, State.Locking) => ()
      }
      monitor.expectMsgPF(hint = "Locking -> Joining") {
        case FSM.Transition(_, State.Locking, State.Joining) => ()
      }
      monitor.expectMsgPF(hint = "Joining -> AddingSelf") {
        case FSM.Transition(_, State.Joining, State.AddingSelf) => ()
      }
      monitor.expectMsgPF(hint = "AddingSelf -> RefreshScheduled") {
        case FSM.Transition(_, State.AddingSelf, State.RefreshScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RefreshScheduled -> Refreshing") {
        case FSM.Transition(_, State.RefreshScheduled, State.Refreshing) => ()
      }

      monitor.expectMsgPF(hint = "Refreshing -> RefreshScheduled") {
        case FSM.Transition(_, State.Refreshing, State.RefreshScheduled) => ()
      }
      monitor.expectMsgPF(hint = "RefreshScheduled -> Refreshing") {
        case FSM.Transition(_, State.RefreshScheduled, State.Refreshing) => ()
      }

      (1 to nrOfRetries + 1) foreach { _ =>
        monitor.expectMsgPF(hint = "Refreshing -> RetryScheduled") {
          case FSM.Transition(_, State.Refreshing, State.RetryScheduled) => ()
        }
        monitor.expectMsgPF(hint = "RetryScheduled -> Refreshing") {
          case FSM.Transition(_, State.RetryScheduled, State.Refreshing) => ()
        }
      }
      monitor.expectMsgPF(hint = "Refreshing -> RefreshScheduled") {
        case FSM.Transition(_, State.Refreshing, State.RefreshScheduled) => ()
      }
    }
  }

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate, Duration.Inf)
    super.afterAll()
  }

  private def boom() = Future.failed(new Exception("BOOM"))

  private def noNodes() = Future.successful(Set.empty[Address])
}
