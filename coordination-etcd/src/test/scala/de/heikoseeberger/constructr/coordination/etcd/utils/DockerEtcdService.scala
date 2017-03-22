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

package de.heikoseeberger.constructr.coordination.etcd.utils

import com.whisk.docker.{ DockerContainer, DockerKit, DockerReadyChecker }

trait DockerEtcdService extends DockerKit {
  val DefaultEtcdPort = 2379

  val etcdContainer = DockerContainer("quay.io/coreos/etcd:v2.3.7")
    .withPorts(DefaultEtcdPort -> Some(DefaultEtcdPort))
    .withReadyChecker(DockerReadyChecker.LogLineContains(
      "setting up the initial cluster version to"))
    .withCommand("--listen-client-urls",
                 "http://0.0.0.0:2379",
                 "--advertise-client-urls",
                 "http://0.0.0.0:2379")

  abstract override def dockerContainers: List[DockerContainer] =
    etcdContainer :: super.dockerContainers
}
