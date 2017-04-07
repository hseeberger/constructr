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
import akka.actor.{ ActorSystem, Address, AddressFromURIString }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{ Get, Put }
import akka.http.scaladsl.model.StatusCodes.{ Created, NotFound, OK, PreconditionFailed }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, ResponseEntity, StatusCode, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64.{ getUrlDecoder, getUrlEncoder }
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }

object EtcdCoordination {

  final case class UnexpectedStatusCode(uri: Uri, statusCode: StatusCode)
      extends RuntimeException(
        s"Unexpected status code $statusCode for URI $uri"
      )

  private def toSeconds(duration: Duration) = (duration.toSeconds + 1).toString
}

final class EtcdCoordination(clusterName: String, system: ActorSystem) extends Coordination {
  import EtcdCoordination._

  private implicit val mat = ActorMaterializer()(system)

  import mat.executionContext

  private val kvUri = {
    val host = system.settings.config.getString("constructr.coordination.host")
    val port = system.settings.config.getInt("constructr.coordination.port")
    Uri(s"http://$host:$port/v2/keys")
  }

  private val baseUri = kvUri.withPath(kvUri.path / "constructr" / clusterName)

  private val nodesUri = baseUri.withPath(baseUri.path / "nodes")

  override def getNodes() = {
    def unmarshalNodes(entity: ResponseEntity) = {
      def toNodes(s: String) = {
        def jsonToNode(json: Json) = {
          val init = nodesUri.path.toString.stripPrefix(kvUri.path.toString)
          val key =
            json.hcursor
              .get[String]("key")
              .fold(throw _, identity)
              .stripPrefix(s"$init/")
          val uri = new String(getUrlDecoder.decode(key), UTF_8)
          AddressFromURIString(uri)
        }
        import cats.syntax.either._ // for Scala 2.11
        parse(s)
          .fold(throw _, identity)
          .hcursor
          .downField("node")
          .get[Set[Json]]("nodes")
          .getOrElse(Set.empty)
          .map(jsonToNode)
      }
      Unmarshal(entity).to[String].map(toNodes)
    }
    responseFor(Get(nodesUri)).flatMap {
      case HttpResponse(OK, _, entity, _) =>
        unmarshalNodes(entity)
      case HttpResponse(NotFound, _, entity, _) =>
        ignore(entity).map(_ => Set.empty)
      case HttpResponse(other, _, entity, _) =>
        ignore(entity).map(_ => throw UnexpectedStatusCode(nodesUri, other))
    }
  }

  override def lock(self: Address, ttl: FiniteDuration) = {
    val lockUri =
      baseUri
        .withPath(baseUri.path / "lock")
        .withQuery(Uri.Query("value" -> self.toString))
    def readLock() = {
      def unmarshalLockHolder(entity: ResponseEntity) = {
        def toLockHolder(s: String) =
          parse(s)
            .fold(throw _, identity)
            .hcursor
            .downField("node")
            .get[String]("value")
            .fold(throw _, identity)
        Unmarshal(entity).to[String].map(toLockHolder)
      }
      responseFor(Get(lockUri)).flatMap {
        case HttpResponse(OK, _, entity, _) =>
          unmarshalLockHolder(entity).map(Some(_))
        case HttpResponse(NotFound, _, entity, _) =>
          ignore(entity).map(_ => None)
        case HttpResponse(other, _, entity, _) =>
          ignore(entity).map(_ => throw UnexpectedStatusCode(nodesUri, other))
      }
    }
    def writeLock() = {
      val uri =
        lockUri.withQuery(
          ("prevExist" -> "false") +: ("ttl" -> toSeconds(ttl)) +:
          Uri.Query(lockUri.rawQueryString)
        )
      responseFor(Put(uri)).flatMap {
        case HttpResponse(Created, _, entity, _) =>
          ignore(entity).map(_ => true)
        case HttpResponse(PreconditionFailed, _, entity, _) =>
          ignore(entity).map(_ => false)
        case HttpResponse(other, _, entity, _) =>
          ignore(entity).map(_ => throw UnexpectedStatusCode(lockUri, other))
      }
    }
    def updateLock(lockHolder: String) = {
      val uri =
        lockUri.withQuery(
          ("prevValue" -> lockHolder) +: ("ttl" -> toSeconds(ttl)) +:
          Uri.Query(lockUri.rawQueryString)
        )
      responseFor(Put(uri)).flatMap {
        case HttpResponse(OK, _, entity, _) =>
          ignore(entity).map(_ => true)
        case HttpResponse(PreconditionFailed, _, entity, _) =>
          ignore(entity).map(_ => false)
        case HttpResponse(other, _, entity, _) =>
          ignore(entity).map(_ => throw UnexpectedStatusCode(lockUri, other))
      }
    }
    readLock().flatMap {
      case Some(h) if h == self.toString => updateLock(h)
      case Some(_)                       => Future.successful(false)
      case None                          => writeLock()
    }
  }

  override def addSelf(self: Address, ttl: FiniteDuration) =
    addSelfOrRefresh(self, ttl)

  override def refresh(self: Address, ttl: FiniteDuration) =
    addSelfOrRefresh(self, ttl)

  private def addSelfOrRefresh(self: Address, ttl: FiniteDuration) = {
    val node  = getUrlEncoder.encodeToString(self.toString.getBytes(UTF_8))
    val query = Uri.Query("ttl" -> toSeconds(ttl), "value" -> self.toString)
    val uri   = nodesUri.withPath(nodesUri.path / node).withQuery(query)
    responseFor(Put(uri)).flatMap {
      case HttpResponse(OK | Created, _, entity, _) =>
        ignore(entity).map(_ => Done)
      case HttpResponse(other, _, entity, _) =>
        ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  private def responseFor(request: HttpRequest) =
    Http(system).singleRequest(request)

  private def ignore(entity: ResponseEntity) =
    entity.dataBytes.runWith(Sink.ignore)
}
