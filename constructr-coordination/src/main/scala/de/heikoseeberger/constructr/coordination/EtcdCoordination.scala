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

import akka.http.scaladsl.client.RequestBuilding.{ Get, Put }
import akka.http.scaladsl.model.StatusCodes.{ Created, NotFound, OK, PreconditionFailed }
import akka.http.scaladsl.model.{ HttpResponse, ResponseEntity, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

final class EtcdCoordination(prefix: String, clusterName: String, host: String, port: Int)(implicit sendFlow: Coordination.SendFlow)
    extends Coordination[Coordination.Backend.Etcd.type] {
  import Coordination._

  private val kvUri = Uri(s"/v2/keys")

  private val baseUri = kvUri.withPath(kvUri.path / "constructr" / prefix / clusterName)

  private val nodesUri = baseUri.withPath(baseUri.path / "nodes")

  override def getNodes[N: NodeSerialization]()(implicit ec: ExecutionContext, mat: Materializer) = {
    def unmarshalNodes(entity: ResponseEntity) = {
      def toNodes(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        def jsonToNode(json: Json) = {
          val init = nodesUri.path.toString.stripPrefix(kvUri.path.toString)
          val key = json.key.as[String].stripPrefix(s"$init/")
          NodeSerialization.fromBytes(decode(key))
        }
        Json.parse(s).node match {
          case json"""{ "nodes": $nodes }""" => nodes.as[Vector[Json]].map(jsonToNode)
          case _                             => Vector.empty
        }
      }
      Unmarshal(entity).to[String].map(toNodes)
    }
    send(Get(nodesUri)).flatMap {
      case HttpResponse(OK, _, entity, _)       => unmarshalNodes(entity)
      case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => Vector.empty)
      case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(nodesUri, other))
    }
  }

  override def lock[N](self: N, ttl: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer) = {
    val lockUri = baseUri.withPath(baseUri.path / "lock").withQuery(Uri.Query("value" -> self.toString))
    def readLock() = {
      def unmarshalLockHolder(entity: ResponseEntity) = {
        def toLockHolder(s: String) = {
          import rapture.json._
          import rapture.json.jsonBackends.spray._
          Json.parse(s).node.value.as[String]
        }
        Unmarshal(entity).to[String].map(toLockHolder)
      }
      send(Get(lockUri)).flatMap {
        case HttpResponse(OK, _, entity, _)       => unmarshalLockHolder(entity).map(Some(_))
        case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => None)
        case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(nodesUri, other))
      }
    }
    def writeLock() = {
      val uri = lockUri.withQuery(("prevExist" -> "false") +: ("ttl" -> toSeconds(ttl)) +: Uri.Query(lockUri.rawQueryString))
      send(Put(uri)).flatMap {
        case HttpResponse(Created, _, entity, _)            => ignore(entity).map(_ => LockResult.Success)
        case HttpResponse(PreconditionFailed, _, entity, _) => ignore(entity).map(_ => LockResult.Failure)
        case HttpResponse(other, _, entity, _)              => ignore(entity).map(_ => throw UnexpectedStatusCode(lockUri, other))
      }
    }
    def updateLock(lockHolder: String) = {
      val uri = lockUri.withQuery(("prevValue" -> lockHolder) +: ("ttl" -> toSeconds(ttl)) +: Uri.Query(lockUri.rawQueryString))
      send(Put(uri)).flatMap {
        case HttpResponse(OK, _, entity, _)                 => ignore(entity).map(_ => LockResult.Success)
        case HttpResponse(PreconditionFailed, _, entity, _) => ignore(entity).map(_ => LockResult.Failure)
        case HttpResponse(other, _, entity, _)              => ignore(entity).map(_ => throw UnexpectedStatusCode(lockUri, other))
      }
    }
    readLock().flatMap {
      case Some(lockHolder) if lockHolder == self.toString => updateLock(lockHolder)
      case Some(_)                                         => Future.successful(LockResult.Failure)
      case None                                            => writeLock()
    }
  }

  override def addSelf[N: NodeSerialization](self: N, ttl: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer) = {
    val uri = addOrRefreshUri(self, ttl)
    send(Put(uri)).flatMap {
      case HttpResponse(OK | Created, _, entity, _) => ignore(entity).map(_ => SelfAdded[Coordination.Backend.Etcd.type](None))
      case HttpResponse(other, _, entity, _)        => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  override def refresh[N: NodeSerialization](self: N, ttl: FiniteDuration, context: None.type)(implicit ec: ExecutionContext, mat: Materializer) = {
    val uri = addOrRefreshUri(self, ttl)
    send(Put(uri)).flatMap {
      case HttpResponse(OK | Created, _, entity, _) => ignore(entity).map(_ => Refreshed)
      case HttpResponse(other, _, entity, _)        => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  override def initialBackendContext = None

  private def addOrRefreshUri[N: NodeSerialization](self: N, ttl: FiniteDuration) = nodesUri
    .withPath(nodesUri.path / encode(NodeSerialization.toBytes(self)))
    .withQuery(Uri.Query("ttl" -> toSeconds(ttl), "value" -> self.toString))
}
