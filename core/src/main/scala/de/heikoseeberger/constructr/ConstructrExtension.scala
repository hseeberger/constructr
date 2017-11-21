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

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }

object ConstructrExtension extends ExtensionId[ConstructrExtension] with ExtensionIdProvider {

  override def lookup(): ExtensionId[ConstructrExtension] = ConstructrExtension

  override def createExtension(system: ExtendedActorSystem): ConstructrExtension =
    new ConstructrExtension(system)

  /**
    * Java API
    */
  override def get(system: ActorSystem): ConstructrExtension = super.get(system)
}

final class ConstructrExtension private (system: ExtendedActorSystem) extends Extension {

  private[this] val supervisor = system.systemActorOf(Constructr.props, Constructr.Name)

  def registerOnFailure[T](code: => T): Unit = {
    val callback = new Runnable {
      override def run(): Unit = code
    }
    registerOnFailure(callback)
  }

  def registerOnFailure(callback: Runnable): Unit =
    supervisor ! Constructr.RegisterFailureHandler(callback)
}
