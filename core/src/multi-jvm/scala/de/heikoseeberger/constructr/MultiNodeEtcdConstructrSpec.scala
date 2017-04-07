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

import akka.actor.{ Address, AddressFromURIString }
import io.circe.Json
import io.circe.parser.parse
import java.util.Base64

class MultiNodeEtcdConstructrSpecMultiJvmNode1 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode2 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode3 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode4 extends MultiNodeEtcdConstructrSpec
class MultiNodeEtcdConstructrSpecMultiJvmNode5 extends MultiNodeEtcdConstructrSpec

object MultiNodeEtcdConstructrSpec {
  def toNodes(s: String): Set[Address] = {
    def jsonToNode(json: Json) = {
      val key =
        json.hcursor
          .get[String]("key")
          .fold(throw _, identity)
          .stripPrefix("/constructr/MultiNodeConstructrSpec/nodes/")
      AddressFromURIString(new String(Base64.getUrlDecoder.decode(key)))
    }
    import cats.syntax.either._ // for Scala 2.11
    parse(s)
      .fold(throw _, identity)
      .hcursor
      .downField("node")
      .get[Set[Json]]("nodes")
      .getOrElse(Set.empty)
      .map(jsonToNode)
  }
}

abstract class MultiNodeEtcdConstructrSpec
    extends MultiNodeConstructrSpec(
      2379,
      "/v2/keys/constructr?recursive=true",
      "/v2/keys/constructr/MultiNodeConstructrSpec/nodes",
      MultiNodeEtcdConstructrSpec.toNodes
    )
