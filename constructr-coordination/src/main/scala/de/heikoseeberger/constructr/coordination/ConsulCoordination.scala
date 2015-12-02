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
import akka.http.scaladsl.model.StatusCodes.{ NotFound, OK }
import akka.http.scaladsl.model.{ HttpResponse, ResponseEntity, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

final class ConsulCoordination(prefix: String, clusterName: String, host: String, port: Int)(implicit sendFlow: Coordination.SendFlow)
    extends Coordination[Coordination.Backend.Consul.type] {
  import Coordination._

  private val v1Uri = Uri("/v1")

  private val kvUri = v1Uri.withPath(v1Uri.path / "kv")

  private val sessionUri = v1Uri.withPath(v1Uri.path / "session")

  private val baseUri = kvUri.withPath(kvUri.path / "constructr" / prefix / clusterName)

  private val nodesUri = baseUri.withPath(baseUri.path / "nodes")

  override def getNodes[A: AddressSerialization]()(implicit ec: ExecutionContext, mat: Materializer): Future[List[A]] = {
    def unmarshalNodes(entity: ResponseEntity) = {
      def toNodes(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        def jsonToNode(json: Json) = {
          val init = nodesUri.path.toString.stripPrefix(kvUri.path.toString)
          val key = json.Key.as[String].substring(init.length)
          implicitly[AddressSerialization[A]].fromBytes(decode(key))
        }
        Json.parse(s).as[List[Json]].map(jsonToNode)
      }
      Unmarshal(entity).to[String].map(toNodes)
    }
    val uri = nodesUri.withQuery(Uri.Query("recurse"))
    send(Get(uri)).flatMap {
      case HttpResponse(OK, _, entity, _)       => unmarshalNodes(entity)
      case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => Nil)
      case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(other))
    }
  }

  override def lock(ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer): Future[LockResult] = {
    def uri(sessionId: String) = baseUri
      .withPath(baseUri.path / "lock")
      .withQuery(Uri.Query("acquire" -> sessionId))
    val responseAndSession = for {
      sessionId <- createSession(ttl)
      response <- send(Put(uri(sessionId)))
    } yield (response, sessionId)
    responseAndSession.flatMap {
      case (HttpResponse(OK, _, entity, _), _) =>
        Unmarshal(entity).to[String]
          .map(_.toBoolean)
          .map(isLocked => if (isLocked) LockResult.Success else LockResult.Failure)
      case (HttpResponse(other, _, entity, _), _) =>
        ignore(entity).map(_ => throw UnexpectedStatusCode(other))
    }
  }

  override def addSelf[A: AddressSerialization](self: A, ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer) = {
    def uri(sessionId: String) = nodesUri
      .withPath(nodesUri.path / encode(implicitly[AddressSerialization[A]].toBytes(self)))
      .withQuery(Uri.Query("acquire" -> sessionId))
    val responseAndSession = for {
      sessionId <- createSession(ttl)
      response <- send(Put(uri(sessionId)))
    } yield (response, sessionId)
    responseAndSession.flatMap {
      case (HttpResponse(OK, _, entity, _), sessionId) => ignore(entity).map(_ => SelfAdded[Coordination.Backend.Consul.type](sessionId))
      case (HttpResponse(other, _, entity, _), _)      => ignore(entity).map(_ => throw UnexpectedStatusCode(other))
    }
  }

  override def refresh[A: AddressSerialization](self: A, ttl: Duration, sessionId: String)(implicit ec: ExecutionContext, mat: Materializer) = {
    val uri = sessionUri.withPath(sessionUri.path / "renew" / sessionId)
    send(Put(uri)).flatMap {
      case HttpResponse(OK, _, entity, _)    => ignore(entity).map(_ => Refreshed[Coordination.Backend.Consul.type](sessionId))
      case HttpResponse(other, _, entity, _) => ignore(entity).map(_ => throw UnexpectedStatusCode(other))
    }
  }

  override def initialBackendContext = ""

  private def createSession(ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer) = {
    def unmarshalSession(entity: ResponseEntity) = {
      def toSession(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        Json.parse(s).ID.as[String]
      }
      Unmarshal(entity).to[String].map(toSession)
    }
    val createSessionUri = sessionUri
      .withPath(sessionUri.path / "create")
      .withQuery(Uri.Query("Behaviour" -> "delete", "ttl" -> toSeconds(ttl)))
    send(Put(createSessionUri)).flatMap {
      case HttpResponse(OK, _, entity, _)    => unmarshalSession(entity)
      case HttpResponse(other, _, entity, _) => ignore(entity).map(_ => throw UnexpectedStatusCode(other))
    }
  }
}
