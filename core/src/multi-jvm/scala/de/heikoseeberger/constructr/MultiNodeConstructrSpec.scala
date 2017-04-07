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

import akka.actor.ActorDSL.{ actor, Act }
import akka.actor.Address
import akka.cluster.{ Cluster, ClusterEvent }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes.{ NotFound, OK }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.stream.ActorMaterializer
import akka.testkit.TestDuration
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, FreeSpecLike, Matchers }
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object ConstructrMultiNodeConfig {
  val coordinationHost = {
    val dockerHostPattern = """tcp://(\S+):\d{1,5}""".r
    sys.env
      .get("DOCKER_HOST")
      .collect { case dockerHostPattern(address) => address }
      .getOrElse("127.0.0.1")
  }
}

class ConstructrMultiNodeConfig(coordinationPort: Int) extends MultiNodeConfig {
  import ConstructrMultiNodeConfig._

  commonConfig(ConfigFactory.load())
  for (n <- 1.to(5)) {
    val port = 2550 + n
    nodeConfig(role(port.toString))(
      ConfigFactory.parseString(
        s"""|akka.actor.provider            = akka.cluster.ClusterActorRefProvider
          |akka.remote.netty.tcp.hostname = "127.0.0.1"
          |akka.remote.netty.tcp.port     = $port
          |constructr.coordination.host   = $coordinationHost
          |constructr.coordination.port   = $coordinationPort
          |""".stripMargin
      )
    )
  }
}

abstract class MultiNodeConstructrSpec(
    coordinationPort: Int,
    delete: String,
    get: String,
    toNodes: String => Set[Address]
) extends MultiNodeSpec(new ConstructrMultiNodeConfig(coordinationPort))
    with FreeSpecLike
    with Matchers
    with BeforeAndAfterAll {
  import ConstructrMultiNodeConfig._
  import RequestBuilding._
  import system.dispatcher

  implicit val mat = ActorMaterializer()

  "Constructr should manage an Akka cluster" in {
    runOn(roles.head) {
      within(20.seconds.dilated) {
        awaitAssert {
          val coordinationStatus =
            Await.result(
              Http()
                .singleRequest(
                  Delete(s"http://$coordinationHost:$coordinationPort$delete")
                )
                .map(_.status),
              5.seconds.dilated // As this is the first request fired via `singleRequest`, creating the pool takes some time (probably)
            )
          coordinationStatus should (be(OK) or be(NotFound))
        }
      }
    }

    enterBarrier("coordination-started")

    ConstructrExtension(system)
    val listener = actor(new Act {
      import ClusterEvent._
      var isMember = false
      Cluster(context.system).subscribe(self,
                                        InitialStateAsEvents,
                                        classOf[MemberJoined],
                                        classOf[MemberUp])
      become {
        case "isMember" => sender() ! isMember

        case MemberJoined(member) if member.address == Cluster(context.system).selfAddress =>
          isMember = true

        case MemberUp(member) if member.address == Cluster(context.system).selfAddress =>
          isMember = true
      }
    })
    within(20.seconds.dilated) {
      awaitAssert {
        implicit val timeout = Timeout(1.second.dilated)
        val isMember         = Await.result((listener ? "isMember").mapTo[Boolean], 1.second.dilated)
        isMember shouldBe true
      }
    }

    enterBarrier("cluster-formed")

    within(5.seconds.dilated) {
      awaitAssert {
        val constructrNodes =
          Await.result(
            Http()
              .singleRequest(
                Get(s"http://$coordinationHost:$coordinationPort$get")
              )
              .flatMap(Unmarshal(_).to[String].map(toNodes)),
            1.second.dilated
          )
        val ports = constructrNodes.flatMap(_.port)
        ports shouldBe roles.to[Set].map(_.name.toInt)
      }
    }

    enterBarrier("done")
  }

  override def initialParticipants = roles.size

  override protected def beforeAll() = {
    super.beforeAll()
    multiNodeSpecBeforeAll()
  }

  override protected def afterAll() = {
    multiNodeSpecAfterAll()
    super.afterAll()
  }
}
