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

package de.heikoseeberger.constructr.machine

import akka.actor.{ ActorSystem, FSM, Props }
import akka.pattern.{ after => akkaAfter }
import akka.stream.ActorMaterializer
import akka.testkit.{ TestDuration, TestProbe }
import de.heikoseeberger.constructr.coordination.Coordination
import java.nio.charset.StandardCharsets
import org.scalamock.scalatest.MockFactory
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }
import scala.concurrent.{ Await, Future }

final class ConstructrMachineSpec extends WordSpec with Matchers with BeforeAndAfterAll with MockFactory {
  import ConstructrMachine._

  private implicit val system = ActorSystem()
  private implicit val mat = ActorMaterializer()
  import system.dispatcher

  implicit val stringNodeSerialization = new Coordination.NodeSerialization[String] {
    override def fromBytes(bytes: Array[Byte]) = new String(bytes, StandardCharsets.UTF_8)
    override def toBytes(s: String) = s.getBytes(StandardCharsets.UTF_8)
  }

  "ConstructrMachine" should {
    "retry the given number of retries and then fail" in {
      val monitor = TestProbe()

      val coordination = mock[Coordination[Coordination.Backend.Etcd.type]]
      inSequence {
        (coordination.initialBackendContext _).expects().returns(())

        (coordination.getNodes()(_: Coordination.NodeSerialization[String]))
          .expects(*)
          .returns(akkaAfter(1.hour.dilated, system.scheduler)(Future(Vector.empty)))
        (coordination.getNodes()(_: Coordination.NodeSerialization[String]))
          .expects(*)
          .returns(Future.failed(new Exception("BOOM")))
      }

      val machine = system.actorOf(Props(new ConstructrMachine[String, Coordination.Backend.Etcd.type](
        selfNode = "self",
        coordination = coordination,
        coordinationTimeout = 100.millis.dilated,
        nrOfRetries = 1,
        retryDelay = 100.millis.dilated,
        refreshInterval = 1.second.dilated,
        ttlFactor = 1.5,
        maxNrOfSeedNodes = 3,
        joinTimeout = 100.millis.dilated
      ) {
        import ConstructrMachine._
        override protected def intoJoiningHandler() = ()
        override protected def joiningFunction = { case Event(StateTimeout, _) => goto(State.AddingSelf) }
        override protected def outOfJoiningHandler() = ()
      }))
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
      val monitor = TestProbe()
      val coordination = mock[Coordination[Coordination.Backend.Etcd.type]]
      inSequence {
        (coordination.initialBackendContext _).expects().returns(())

        (coordination.getNodes()(_: Coordination.NodeSerialization[String]))
          .expects(*)
          .returns(akkaAfter(1.hour.dilated, system.scheduler)(Future.failed(new Exception("BOOM"))))
        (coordination.getNodes()(_: Coordination.NodeSerialization[String]))
          .expects(*)
          .returns(Future.failed(new Exception("BOOM")))
        (coordination.getNodes()(_: Coordination.NodeSerialization[String]))
          .expects(*)
          .returns(Future.successful(Vector.empty))

        (coordination.lock(_: String, _: FiniteDuration))
          .expects("self", 1650.millis.dilated)
          .returns(akkaAfter(1.hour.dilated, system.scheduler)(Future.failed(new Exception("BOOM"))))
        (coordination.lock(_: String, _: FiniteDuration))
          .expects("self", 1650.millis.dilated)
          .returns(Future.failed(new Exception("BOOM")))
        (coordination.lock(_: String, _: FiniteDuration))
          .expects("self", 1650.millis.dilated)
          .returns(Future.successful(Coordination.LockResult.Failure))

        (coordination.getNodes()(_: Coordination.NodeSerialization[String]))
          .expects(*)
          .returns(Future.successful(Vector.empty))

        (coordination.lock(_: String, _: FiniteDuration))
          .expects("self", 1650.millis.dilated)
          .returns(Future.successful(Coordination.LockResult.Success))

        (coordination.addSelf(_: String, _: FiniteDuration)(_: Coordination.NodeSerialization[String]))
          .expects("self", 1500.millis, *)
          .returns(akkaAfter(1.hour.dilated, system.scheduler)(Future.failed(new Exception("BOOM"))))
        (coordination.addSelf(_: String, _: FiniteDuration)(_: Coordination.NodeSerialization[String]))
          .expects("self", 1500.millis, *)
          .returns(Future.failed(new Exception("BOOM")))
        (coordination.addSelf(_: String, _: FiniteDuration)(_: Coordination.NodeSerialization[String]))
          .expects("self", 1500.millis, *)
          .returns(Future.successful(Coordination.SelfAdded[Coordination.Backend.Etcd.type](())))

        (coordination.refresh(_: String, _: FiniteDuration, _: Unit)(_: Coordination.NodeSerialization[String]))
          .expects("self", 1500.millis, (), *)
          .returns(akkaAfter(1.hour.dilated, system.scheduler)(Future.failed(new Exception("BOOM"))))
        (coordination.refresh(_: String, _: FiniteDuration, _: Unit)(_: Coordination.NodeSerialization[String]))
          .expects("self", 1500.millis, (), *)
          .returns(Future.failed(new Exception("BOOM")))
        (coordination.refresh(_: String, _: FiniteDuration, _: Unit)(_: Coordination.NodeSerialization[String]))
          .expects("self", 1500.millis, (), *)
          .anyNumberOfTimes
          .returns(Future.successful(Coordination.Refreshed))
      }

      val machine = system.actorOf(Props(new ConstructrMachine(
        selfNode = "self",
        coordination = coordination,
        coordinationTimeout = 100.millis.dilated,
        nrOfRetries = 2,
        retryDelay = 100.millis.dilated,
        refreshInterval = 1.second.dilated,
        ttlFactor = 1.5,
        maxNrOfSeedNodes = 3,
        joinTimeout = 100.millis.dilated
      ) {
        import ConstructrMachine._
        override protected def intoJoiningHandler() = ()
        override protected def joiningFunction = { case Event(StateTimeout, _) => goto(State.AddingSelf) }
        override protected def outOfJoiningHandler() = ()
      }))
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
      monitor.expectMsgPF(hint = "Locking -> GettingNodes") {
        case FSM.Transition(_, State.Locking, State.GettingNodes) => ()
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
      monitor.expectMsgPF(hint = "Refreshing -> Refreshing") {
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
  }

  override protected def afterAll() = {
    Await.ready(system.terminate, Duration.Inf)
    super.afterAll()
  }
}
