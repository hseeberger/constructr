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

import akka.actor.ActorDSL.{ Act, actor }
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
import scala.sys.process.{ ProcessLogger, stringToProcess }

object EtcdConstructrMultiNodeConfig extends MultiNodeConfig {

  val host = "docker-machine ip default".!!.trim

  val nodes = 1.to(5).to[List].map(n => node(2550 + n))

  private def node(port: Int) = {
    commonConfig(ConfigFactory.load())
    val node = role(port.toString)
    nodeConfig(node)(ConfigFactory.parseString(
      s"""|akka.actor.provider               = akka.cluster.ClusterActorRefProvider
          |akka.loggers                      = ["de.heikoseeberger.akkalog4j.Log4jLogger"]
          |akka.loglevel                     = "DEBUG"
          |akka.remote.netty.tcp.hostname    = "127.0.0.1"
          |akka.remote.netty.tcp.port        = $port
          |constructr.akka.coordination.host = $host""".stripMargin
    ))
    node
  }
}

class MultiNodeEtcdConstructrSpecMultiJvmNode1 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode2 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode3 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode4 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode5 extends MultiNodeEtcdConstructrSpec

abstract class MultiNodeEtcdConstructrSpec extends MultiNodeSpec(EtcdConstructrMultiNodeConfig)
    with FreeSpecLike with Matchers with BeforeAndAfterAll {
  import EtcdConstructrMultiNodeConfig._
  import RequestBuilding._
  import system.dispatcher

  implicit val mat = ActorMaterializer()

  "Constructr should manage an Akka cluster" in {
    runOn(nodes.head) {
      "docker rm -f constructr-etcd".!(ProcessLogger(_ => ()))
      s"""docker run --name constructr-etcd -d -p 2379:2379 quay.io/coreos/etcd:v2.2.1 -advertise-client-urls http://$host:2379 -listen-client-urls http://0.0.0.0:2379""".!

      within(20.seconds.dilated) {
        awaitAssert {
          val etcdStatus = Await.result(
            Http().singleRequest(Delete(s"http://$host:2379/v2/keys/constructr/akka?recursive=true")).map(_.status),
            5.seconds.dilated // As this is the first request fired via `singleRequest`, creating the pool takes some time (probably)
          )
          etcdStatus should (be(OK) or be(NotFound))
        }
      }
    }

    enterBarrier("etcd-started")

    ConstructrExtension(system)
    val listener = actor(new Act {
      var isMember = false
      Cluster(context.system).subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.MemberUp])
      become {
        case ClusterEvent.MemberUp(member) if member.address == Cluster(context.system).selfAddress => isMember = true
        case _                                                                                      => sender() ! isMember
      }
    })
    within(20.seconds.dilated) {
      awaitAssert {
        implicit val timeout = Timeout(1.second.dilated)
        val isMember = Await.result((listener ? "isMember").mapTo[Boolean], 1.second.dilated)
        isMember shouldBe true
      }
    }

    enterBarrier("cluster-formed")

    within(5.seconds.dilated) {
      awaitAssert {
        val constructrNodes = Await.result(
          Http()
            .singleRequest(Get(s"http://$host:2379/v2/keys/constructr/akka/MultiNodeEtcdConstructrSpec/nodes"))
            .flatMap(resp => Unmarshal(resp.entity).to[String]),
          1.second.dilated
        )
        nodes.to[Set].map(_.name).foreach(node => constructrNodes should include(node))
      }
    }

    enterBarrier("done")

    runOn(nodes.head) {
      "docker rm -f constructr-etcd".!
    }
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
