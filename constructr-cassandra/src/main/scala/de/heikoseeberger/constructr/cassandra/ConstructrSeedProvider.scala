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

package de.heikoseeberger.constructr.cassandra

import akka.actor.ActorSystem
import akka.pattern.ask
import java.util.{ Map => JMap }
import org.apache.cassandra.locator.SeedProvider
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.concurrent.Await

class ConstructrSeedProvider(params: JMap[String, String]) extends SeedProvider {

  private val system = ActorSystem("constructr-cassandra-system")

  private val constructr = system.actorOf(Constructr.props, Constructr.Name)

  override def getSeeds() = {
    import system.dispatcher
    val timeout = Settings(system).seedProviderTimeout
    val nodes = Await.result(
      constructr
        .ask(Constructr.GetNodes)(timeout)
        .mapTo[Constructr.Nodes]
        .map(_.value),
      timeout
    )
    system.log.info(s"Determined these seed nodes: ${nodes.mkString(", ")}")
    nodes.asJava
  }
}
