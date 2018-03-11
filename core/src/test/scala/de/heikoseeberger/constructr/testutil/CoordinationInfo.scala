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

package de.heikoseeberger.constructr.testutil

object CoordinationInfo {

  def host: String = {
    val dockerHostPattern = """tcp://(\S+):\d{1,5}""".r
    sys.env
      .get("DOCKER_HOST")
      .collect({ case dockerHostPattern(address) => address })
      .getOrElse("127.0.0.1")
  }

  def port: Int = 2379

}
