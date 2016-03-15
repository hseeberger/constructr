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

import com.typesafe.config.Config
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

trait ConstructrMachineSettings {

  final val coordinationTimeout: FiniteDuration = getDuration("constructr.coordination-timeout")

  final val maxNrOfSeedNodes: Int = {
    val maxNrOfSeedNodes = config.getInt("constructr.max-nr-of-seed-nodes")
    if (maxNrOfSeedNodes <= 0) Int.MaxValue else maxNrOfSeedNodes
  }

  final val nrOfRetries: Int = config.getInt("constructr.nr-of-retries")

  final val refreshInterval: FiniteDuration = getDuration("constructr.refresh-interval")

  final val retryDelay: FiniteDuration = getDuration("constructr.retry-delay")

  final val ttlFactor: Double = config.getDouble("constructr.ttl-factor")

  protected def config: Config

  protected def getDuration(key: String): FiniteDuration = FiniteDuration(config.getDuration(key).toNanos, NANOSECONDS)
}
