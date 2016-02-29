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

import akka.actor.Address
import de.heikoseeberger.constructr.coordination.{ Coordination, decode }

class MultiNodeEtcdConstructrSpecMultiJvmNode1 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode2 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode3 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode4 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode5 extends MultiNodeEtcdConstructrSpec

object MultiNodeEtcdConstructrSpec {
  def toNodes(s: String): Set[Address] = {
    import rapture.json._
    import rapture.json.jsonBackends.spray._
    def jsonToNode(json: Json) = {
      val key = json.key.as[String].stripPrefix("/constructr/akka/MultiNodeConstructrSpec/nodes/")
      Coordination.NodeSerialization.fromBytes(decode(key))
    }
    Json.parse(s).node match {
      case json"""{ "nodes": $nodes }""" => nodes.as[Set[Json]].map(jsonToNode)
      case _                             => Set.empty
    }
  }
}

abstract class MultiNodeEtcdConstructrSpec extends MultiNodeConstructrSpec(
  Coordination.Backend.Etcd,
  2379,
  "/v2/keys/constructr/akka?recursive=true",
  "/v2/keys/constructr/akka/MultiNodeConstructrSpec/nodes",
  MultiNodeEtcdConstructrSpec.toNodes
)
