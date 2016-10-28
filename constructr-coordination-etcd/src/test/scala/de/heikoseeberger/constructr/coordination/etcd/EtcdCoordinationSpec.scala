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

package de.heikoseeberger.constructr.coordination.etcd

import akka.Done
import akka.actor.{ ActorSystem, AddressFromURIString }
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.constructr.coordination.Coordination
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }
import scala.concurrent.{ Await, Awaitable }
import scala.util.Random

object EtcdCoordinationSpec {

  private val coordinationHost = {
    val dockerHostPattern = """tcp://(\S+):\d{1,5}""".r
    sys.env
      .get("DOCKER_HOST")
      .collect { case dockerHostPattern(address) => address }
      .getOrElse("127.0.0.1")
  }
}

class EtcdCoordinationSpec
    extends WordSpec
    with Matchers
    with BeforeAndAfterAll {
  import EtcdCoordinationSpec._

  private implicit val system = {
    val config = ConfigFactory
      .parseString(s"constructr.coordination.host = $coordinationHost")
      .withFallback(ConfigFactory.load())
    ActorSystem("default", config)
  }

  private val address = AddressFromURIString("akka.tcp://default@a:2552")

  "EtcdCoordination" should {
    "correctly interact with etcd" in {
      val coordination = new EtcdCoordination(
        randomString(),
        randomString(),
        system): Coordination // Ascription needed for IDEA

      resultOf(coordination.getNodes()) shouldBe 'empty

      resultOf(coordination.lock(address, 10.seconds)) shouldBe true
      resultOf(coordination.lock(address, 10.seconds)) shouldBe true
      resultOf(
        coordination.lock(AddressFromURIString("akka.tcp://default@b:2552"),
                          10.seconds)) shouldBe false

      resultOf(coordination.addSelf(address, 10.seconds)) shouldBe Done
      resultOf(coordination.getNodes()) shouldBe Set(address)

      resultOf(coordination.refresh(address, 1.second)) shouldBe Done
      resultOf(coordination.getNodes()) shouldBe Set(address)

      val probe = TestProbe()
      import probe._
      within(5.seconds) { // 2 seconds should be enough, but who knows hows ...
        awaitAssert {
          resultOf(coordination.getNodes()) shouldBe 'empty
        }
      }
    }
  }

  override protected def afterAll() = {
    Await.ready(system.terminate(), Duration.Inf)
    super.afterAll()
  }

  private def resultOf[A](awaitable: Awaitable[A],
                          max: FiniteDuration = 3.seconds): A =
    Await.result(awaitable, max)

  private def randomString() = math.abs(Random.nextInt).toString
}
