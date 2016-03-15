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
package etcd

import akka.Done
import akka.http.scaladsl.client.RequestBuilding.{ Get, Put }
import akka.http.scaladsl.model.StatusCodes.{ Created, NotFound, OK, PreconditionFailed }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, ResponseEntity, StatusCode, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.config.Config
import java.util.Base64
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }

object EtcdCoordination {

  final case class UnexpectedStatusCode(uri: Uri, statusCode: StatusCode)
    extends RuntimeException(s"Unexpected status code $statusCode for URI $uri")

  private def toSeconds(duration: Duration) = (duration.toSeconds + 1).toString
}

final class EtcdCoordination(
  prefix: String,
  clusterName: String,
  config: Config
)(implicit
  connection: Coordination.Connection,
  mat: Materializer)
    extends Coordination {
  import Coordination._
  import EtcdCoordination._
  import mat.executionContext

  private val kvUri = Uri(s"/v2/keys")

  private val baseUri = kvUri.withPath(kvUri.path / "constructr" / prefix / clusterName)

  private val nodesUri = baseUri.withPath(baseUri.path / "nodes")

  override def getNodes[A: NodeSerialization]() = {
    def unmarshalNodes(entity: ResponseEntity) = {
      def toNodes(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        def jsonToNode(json: Json) = {
          val init = nodesUri.path.toString.stripPrefix(kvUri.path.toString)
          val key = json.key.as[String].stripPrefix(s"$init/")
          NodeSerialization.fromBytes(Base64.getUrlDecoder.decode(key))
        }
        Json.parse(s).node match {
          case json"""{ "nodes": $nodes }""" => nodes.as[Set[Json]].map(jsonToNode)
          case _                             => Set.empty[A]
        }
      }
      Unmarshal(entity).to[String].map(toNodes)
    }
    send(Get(nodesUri)).flatMap {
      case HttpResponse(OK, _, entity, _)       => unmarshalNodes(entity)
      case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => Set.empty)
      case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(nodesUri, other))
    }
  }

  override def lock[A: NodeSerialization](self: A, ttl: FiniteDuration) = {
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
        case HttpResponse(Created, _, entity, _)            => ignore(entity).map(_ => true)
        case HttpResponse(PreconditionFailed, _, entity, _) => ignore(entity).map(_ => false)
        case HttpResponse(other, _, entity, _)              => ignore(entity).map(_ => throw UnexpectedStatusCode(lockUri, other))
      }
    }
    def updateLock(lockHolder: String) = {
      val uri = lockUri.withQuery(("prevValue" -> lockHolder) +: ("ttl" -> toSeconds(ttl)) +: Uri.Query(lockUri.rawQueryString))
      send(Put(uri)).flatMap {
        case HttpResponse(OK, _, entity, _)                 => ignore(entity).map(_ => true)
        case HttpResponse(PreconditionFailed, _, entity, _) => ignore(entity).map(_ => false)
        case HttpResponse(other, _, entity, _)              => ignore(entity).map(_ => throw UnexpectedStatusCode(lockUri, other))
      }
    }
    readLock().flatMap {
      case Some(lockHolder) if lockHolder == self.toString => updateLock(lockHolder)
      case Some(_)                                         => Future.successful(false)
      case None                                            => writeLock()
    }
  }

  override def addSelf[A: NodeSerialization](self: A, ttl: FiniteDuration) = addSelfOrRefresh(self, ttl)

  override def refresh[A: NodeSerialization](self: A, ttl: FiniteDuration) = addSelfOrRefresh(self, ttl)

  private def addSelfOrRefresh[A: NodeSerialization](self: A, ttl: FiniteDuration) = {
    val uri = nodesUri
      .withPath(nodesUri.path / Base64.getUrlEncoder.encodeToString(NodeSerialization.toBytes(self)))
      .withQuery(Uri.Query("ttl" -> toSeconds(ttl), "value" -> self.toString))
    send(Put(uri)).flatMap {
      case HttpResponse(OK | Created, _, entity, _) => ignore(entity).map(_ => Done)
      case HttpResponse(other, _, entity, _)        => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  private def send(request: HttpRequest) = Source.single(request).via(connection).runWith(Sink.head)

  private def ignore(entity: ResponseEntity) = entity.dataBytes.runWith(Sink.ignore)
}
