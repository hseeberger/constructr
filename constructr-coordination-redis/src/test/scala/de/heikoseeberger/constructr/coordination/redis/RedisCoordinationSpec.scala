/*
 * Copyright 2016 Shingo Omura
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
package de.heikoseeberger.constructr.coordination.redis

import java.nio.charset.StandardCharsets._

import akka.Done
import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.constructr.coordination.Coordination
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.{ Awaitable, Await }
import scala.concurrent.duration._
import scala.util.Random

object RedisCoordinationSpec {
  import Coordination._

  private implicit val stringNodeSerialization = new NodeSerialization[String] {
    override def fromBytes(bytes: Array[Byte]) = new String(bytes, UTF_8)
    override def toBytes(s: String) = s.getBytes(UTF_8)
  }

  // this test assumes redis server is up on DOCKER_HOST or localhost(127.0.0.1)
  // below command would be help:
  //   $ docker run --name some-redis -p 6379:6379 -d redis
  private val coordinationHost = {
    val dockerHostPattern = """tcp://(\S+):\d{1,5}""".r
    sys.env.get("DOCKER_HOST")
      .collect { case dockerHostPattern(address) => address }
      .getOrElse("127.0.0.1")
  }
}

class RedisCoordinationSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  import RedisCoordinationSpec._

  private implicit val system = {
    val config = ConfigFactory.parseString(s"constructr.coordination.host = $coordinationHost").withFallback(ConfigFactory.load())
    ActorSystem("default", config)
  }

  "RedisCoordination" should {
    "correctly interact with redis" in {
      val coordination: Coordination = new RedisCoordination(randomString(), randomString(), system)

      resultOf(coordination.getNodes[String]()) shouldBe 'empty

      resultOf(coordination.lock[String]("self", 10.seconds)) shouldBe true
      resultOf(coordination.lock[String]("self", 10.seconds)) shouldBe true
      resultOf(coordination.lock[String]("other", 10.seconds)) shouldBe false

      resultOf(coordination.addSelf[String]("self", 10.seconds)) shouldBe Done
      resultOf(coordination.getNodes[String]()) shouldBe Set("self")

      resultOf(coordination.refresh[String]("self", 1.second)) shouldBe Done
      resultOf(coordination.getNodes[String]()) shouldBe Set("self")

      val probe = TestProbe()
      import probe._
      within(5.seconds) { // 2 seconds should be enough, but who knows hows ...
        awaitAssert {
          resultOf(coordination.getNodes[String]()) shouldBe 'empty
        }
      }
    }
  }

  override protected def afterAll() = {
    Await.ready(system.terminate(), Duration.Inf)
    super.afterAll()
  }

  private def resultOf[A](awaitable: Awaitable[A], max: FiniteDuration = 3.seconds): A = Await.result(awaitable, max)

  private def randomString() = math.abs(Random.nextInt).toString
}
