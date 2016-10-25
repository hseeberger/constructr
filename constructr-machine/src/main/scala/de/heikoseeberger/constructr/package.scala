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

package de.heikoseeberger

import akka.actor.{ Address, AddressFromURIString }
import de.heikoseeberger.constructr.coordination.Coordination
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.{ Duration, FiniteDuration }

package object constructr {

  type Traversable[+A] = scala.collection.immutable.Traversable[A]
  type Iterable[+A]    = scala.collection.immutable.Iterable[A]
  type Seq[+A]         = scala.collection.immutable.Seq[A]
  type IndexedSeq[+A]  = scala.collection.immutable.IndexedSeq[A]

  implicit object AkkaAddressNodeSerialization
      extends Coordination.NodeSerialization[Address] {
    override def fromBytes(bytes: Array[Byte]) =
      AddressFromURIString(new String(bytes, UTF_8))
    override def toBytes(address: Address) = address.toString.getBytes(UTF_8)
  }
}
