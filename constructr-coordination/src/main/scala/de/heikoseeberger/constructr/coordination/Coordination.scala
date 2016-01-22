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

package de.heikoseeberger.constructr.coordination

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCode, Uri }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source, Flow }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

object Coordination {

  type SendFlow = Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]

  sealed trait Backend {
    type Context
  }

  object Backend {
    case object Etcd extends Backend {
      override type Context = None.type
    }
    case object Consul extends Backend {
      type SessionId = String
      override type Context = SessionId
    }
  }

  object NodeSerialization {
    def fromBytes[N: NodeSerialization](bytes: Array[Byte]): N = implicitly[NodeSerialization[N]].fromBytes(bytes)
    def toBytes[N: NodeSerialization](n: N): Array[Byte] = implicitly[NodeSerialization[N]].toBytes(n)
  }
  trait NodeSerialization[N] {
    def fromBytes(bytes: Array[Byte]): N
    def toBytes(n: N): Array[Byte]
  }

  sealed trait LockResult
  object LockResult {
    case object Success extends LockResult
    case object Failure extends LockResult
  }

  case class SelfAdded[B <: Coordination.Backend](context: B#Context)

  case object Refreshed

  case class UnexpectedStatusCode(uri: Uri, statusCode: StatusCode) extends RuntimeException(s"Unexpected status code $statusCode for URI $uri")

  def apply[B <: Coordination.Backend](backend: Backend)(prefix: String, clusterName: String, host: String, port: Int, sendFlow: SendFlow): Coordination[B] =
    backend match {
      case Backend.Etcd   => new EtcdCoordination(prefix, clusterName, host, port)(sendFlow).asInstanceOf[Coordination[B]]
      case Backend.Consul => new ConsulCoordination(prefix, clusterName, host, port)(sendFlow).asInstanceOf[Coordination[B]]
    }

  def send(request: HttpRequest)(implicit sendFlow: SendFlow, mat: Materializer): Future[HttpResponse] =
    Source.single(request).via(sendFlow).runWith(Sink.head)
}

abstract class Coordination[B <: Coordination.Backend] {
  import Coordination._

  def getNodes[N: NodeSerialization]()(implicit ec: ExecutionContext, mat: Materializer): Future[List[N]]

  def lock[N](self: N, ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer): Future[LockResult]

  def addSelf[N: NodeSerialization](self: N, ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer): Future[SelfAdded[B]]

  def refresh[N: NodeSerialization](self: N, ttl: Duration, context: B#Context)(implicit ec: ExecutionContext, mat: Materializer): Future[Refreshed.type]

  def initialBackendContext: B#Context
}
