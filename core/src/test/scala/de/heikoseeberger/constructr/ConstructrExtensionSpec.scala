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

import java.util.UUID

import akka.actor.ActorSystem
import akka.cluster.{ Cluster, MemberStatus }
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.constructr.testutil.CoordinationInfo
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time._
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class ConstructrExtensionSpec
    extends WordSpec
    with Matchers
    with MockitoSugar
    with BeforeAndAfterEach
    with Eventually {

  import ConstructrExtensionSpec._

  private[this] var clusterNodes = Seq.empty[ActorSystem]

  override protected def afterEach(): Unit =
    dispose(clusterNodes)

  "Cluster members managed by ConstructR" should {
    // Happy path
    "successfully join the cluster under normal circumstances" in {
      val clusterName = generateClusterName()
      clusterNodes = 0.until(5).map(i => createClusterNode(clusterName, 15000 + i))
      eventuallyUp(clusterNodes, 10.seconds)
    }

    /*
     * An initial set of nodes joins the cluster and crashes, thus leaving the corresponding seed entries at
     * the backend. A second set of nodes attempts to join the cluster prior to the expiration of the previous
     * seed entries, thus initiating join attempts to seed nodes that are no longer alive. These attempts will
     * continue to fail, until the original seed entries expire, and one of the new nodes manages to register
     * itself as the new seed and formulate a cluster. After that the rest of the nodes will eventually join
     * as well.
     */
    "eventually join the cluster if initial seeds crash" in {
      val clusterName = generateClusterName()
      val basePort    = 15005
      clusterNodes = 0.until(1).map(i => createClusterNode(clusterName, basePort + i))
      eventuallyUp(clusterNodes, 10.seconds)
      dispose(clusterNodes)
      clusterNodes = 1.until(5).map(i => createClusterNode(clusterName, basePort + i))

      Thread.sleep(20.seconds.toMillis)

      for (clusterNode <- clusterNodes) {
        val clusterExtension = Cluster(clusterNode)
        assert(clusterExtension.selfMember.status != MemberStatus.Up,
               s"node ${clusterExtension.selfAddress} is up")
      }

      eventuallyUp(clusterNodes, 40.seconds)
    }

    /*
     * An initial set of nodes joins the cluster and crashes, thus leaving the corresponding seed entries at
     * the backend. A second set of nodes attempts to join the cluster prior to the expiration of the previous
     * seed entries, thus initiating join attempts to seed nodes that are no longer alive. These attempts will
     * continue to fail for a while, until the original set of nodes is restarted, and successfully formulates
     * a cluster. After that the rest of the nodes will eventually join as well.
     */
    "correctly join initial seeds if they crash but recover in time" in {
      val clusterName = generateClusterName()
      val basePort    = 15010
      clusterNodes = 0.until(1).map(i => createClusterNode(clusterName, basePort + i))
      eventuallyUp(clusterNodes, 10.seconds)
      dispose(clusterNodes)
      clusterNodes = 1.until(5).map(i => createClusterNode(clusterName, basePort + i))

      Thread.sleep(10.seconds.toMillis)

      for (clusterNode <- clusterNodes) {
        val clusterExtension = Cluster(clusterNode)
        assert(clusterExtension.selfMember.status != MemberStatus.Up,
               s"node ${clusterExtension.selfAddress} is up")
      }

      clusterNodes = createClusterNode(clusterName, basePort) +: clusterNodes
      eventuallyUp(clusterNodes, 10.seconds)
    }
  }

  private def eventuallyUp(cluster: scala.Seq[ActorSystem], within: FiniteDuration): Unit =
    eventually(timeout(Span(within.toSeconds, Seconds)), interval(Span(2, Seconds))) {
      for (clusterNode <- cluster) {
        val clusterExtension = Cluster(clusterNode)
        assert(clusterExtension.selfMember.status == MemberStatus.Up,
               s"node ${clusterExtension.selfAddress} is not up")
        assert(clusterExtension.state.members.size == cluster.size)
      }
    }
}

object ConstructrExtensionSpec {

  val ClusterNameBase: String = "ConstructrExtensionSpecCluster"

  def generateClusterName(): String = s"$ClusterNameBase-${UUID.randomUUID()}"

  def createClusterNode(name: String, port: Int): ActorSystem = {
    val config = ConfigFactory.parseString(s"""
        | akka {
        |   actor.provider = akka.cluster.ClusterActorRefProvider
        |   cluster.jmx.multi-mbeans-in-same-jvm = on
        |
        |   remote {
        |     netty.tcp {
        |       hostname = "127.0.0.1"
        |       port = $port
        |     }
        |   }
        | }
        |
        | constructr {
        |   coordination {
        |     host = "${CoordinationInfo.host}"
        |     port = ${CoordinationInfo.port}
        |   }
        |
        |   coordination-timeout = 2 seconds
        |   join-timeout = 3 seconds
        |   abort-on-join-timeout = false
        |   nr-of-retries = 1
        |   retry-delay = 2 seconds
        |   refresh-interval = 6 seconds
        | }
      """.stripMargin)
    val system = ActorSystem(name, config)
    ConstructrExtension(system)
    system
  }

  def dispose(clusterNodes: scala.Seq[ActorSystem]): Unit =
    if (clusterNodes.nonEmpty) {
      implicit val executor = ExecutionContext.Implicits.global
      Await.result(Future.traverse(clusterNodes)(_.terminate()), 20.seconds)
    }
}
