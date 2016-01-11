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
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse, ResponseEntity, Uri }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

final class ConsulCoordination(prefix: String, clusterName: String, host: String, port: Int)(implicit sendFlow: Coordination.SendFlow)
    extends Coordination[Coordination.Backend.Consul.type] {
  import Coordination._
  import Backend.Consul.SessionId

  private val v1Uri = Uri("/v1")

  private val kvUri = v1Uri.withPath(v1Uri.path / "kv")

  private val sessionUri = v1Uri.withPath(v1Uri.path / "session")

  private val baseUri = kvUri.withPath(kvUri.path / "constructr" / prefix / clusterName)

  private val nodesUri = baseUri.withPath(baseUri.path / "nodes")

  override def getNodes[N: NodeSerialization]()(implicit ec: ExecutionContext, mat: Materializer): Future[List[N]] = {
    def unmarshalNodes(entity: ResponseEntity) = {
      def toNodes(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        def jsonToNode(json: Json) = {
          val init = nodesUri.path.toString.stripPrefix(kvUri.path.toString)
          val key = json.Key.as[String].substring(init.length)
          implicitly[NodeSerialization[N]].fromBytes(decode(key))
        }
        Json.parse(s).as[List[Json]].map(jsonToNode)
      }
      Unmarshal(entity).to[String].map(toNodes)
    }
    val uri = nodesUri.withQuery(Uri.Query("recurse"))
    send(Get(uri)).flatMap {
      case HttpResponse(OK, _, entity, _)       => unmarshalNodes(entity)
      case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => Nil)
      case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  override def lock[N](self: N, ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer): Future[LockResult] = {
    // TODO Make idempotent wrt retries! Actually a retry should not be fully idempotent, but update to the full TTL.
    val uriLock = baseUri.withPath(baseUri.path / "lock")
    val response = for {
      sessionId <- createSession(ttl)
      result <- putKeyWithSession(uriLock, sessionId)
    } yield result
    response.map(isLocked => if (isLocked) LockResult.Success else LockResult.Failure)
  }

  override def addSelf[N: NodeSerialization](self: N, ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer) = {
    val keyUri = nodesUri.withPath(nodesUri.path / encode(implicitly[NodeSerialization[N]].toBytes(self)))
    val addSelfWithPreviousSession = for {
      Some(sessionId) <- retrieveSessionForKey(keyUri)
      result <- renewSession(sessionId) if result
    } yield sessionId // it will fail if there's no session or the renewal went wrong
    val addSelftWithNewSession = for {
      sessionId <- createSession(ttl)
      result <- putKeyWithSession(keyUri, sessionId) if result
    } yield sessionId // it will fail if it couldn't acquire the key with the new session
    (addSelfWithPreviousSession fallbackTo addSelftWithNewSession).map(SelfAdded[Coordination.Backend.Consul.type])
  }

  override def refresh[N: NodeSerialization](self: N, ttl: Duration, sessionId: String)(implicit ec: ExecutionContext, mat: Materializer) = {
    val uri = sessionUri.withPath(sessionUri.path / "renew" / sessionId)
    send(Put(uri)).flatMap {
      case HttpResponse(OK, _, entity, _)    => ignore(entity).map(_ => Refreshed[Coordination.Backend.Consul.type](sessionId))
      case HttpResponse(other, _, entity, _) => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  override def initialBackendContext = ""

  private def putKeyWithSession(keyUri: Uri, sessionId: SessionId)(implicit ec: ExecutionContext, mat: Materializer): Future[Boolean] = {
    val uri = keyUri.withQuery(Uri.Query("acquire" -> sessionId))
    send(Put(uri)).flatMap {
      case HttpResponse(OK, _, entity, _)    => Unmarshal(entity).to[String].map(_.toBoolean)
      case HttpResponse(other, _, entity, _) => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  private def retrieveSessionForKey(keyUri: Uri)(implicit ec: ExecutionContext, mat: Materializer): Future[Option[SessionId]] = {
    def unmarshalSessionKey(entity: ResponseEntity) = {
      def toSession(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        def jsonToNode(json: Json) = json.Session.as[String]
        Json.parse(s).as[List[Json]].map(jsonToNode).head
      }
      Unmarshal(entity).to[String].map(toSession)
    }
    send(Get(keyUri)).flatMap {
      case HttpResponse(OK, _, entity, _)       => unmarshalSessionKey(entity).map(Some(_))
      case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => None)
      case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(keyUri, other))
    }
  }

  private def renewSession(sessionId: SessionId)(implicit ec: ExecutionContext, mat: Materializer): Future[Boolean] = {
    val uri = sessionUri.withPath(sessionUri.path / "renew" / sessionId)
    send(Put(uri)).flatMap {
      case HttpResponse(OK, _, entity, _)       => ignore(entity).map(_ => true)
      case HttpResponse(NotFound, _, entity, _) => ignore(entity).map(_ => false)
      case HttpResponse(other, _, entity, _)    => ignore(entity).map(_ => throw UnexpectedStatusCode(uri, other))
    }
  }

  private def createSession(ttl: Duration)(implicit ec: ExecutionContext, mat: Materializer): Future[SessionId] = {
    def unmarshalSessionId(entity: ResponseEntity) = {
      def toSession(s: String) = {
        import rapture.json._
        import rapture.json.jsonBackends.spray._
        Json.parse(s).ID.as[String]
      }
      Unmarshal(entity).to[String].map(toSession)
    }
    val createSessionUri = sessionUri.withPath(sessionUri.path / "create")
    val body = HttpEntity(`application/json`, s"""{"behavior": "delete", "ttl": "${toSeconds(ttl)}s"}""")
    send(Put(createSessionUri, body)).flatMap {
      case HttpResponse(OK, _, entity, _)    => unmarshalSessionId(entity)
      case HttpResponse(other, _, entity, _) => ignore(entity).map(_ => throw UnexpectedStatusCode(createSessionUri, other))
    }
  }
}