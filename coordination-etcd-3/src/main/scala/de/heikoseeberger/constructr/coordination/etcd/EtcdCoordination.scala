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

import akka.actor.{ ActorSystem, Address }
import com.google.protobuf.ByteString
import etcdserverpb.rpc.Compare.{ CompareResult, CompareTarget, TargetUnion }
import etcdserverpb.rpc.ResponseOp.Response.ResponseRange
import etcdserverpb.rpc.{
  Compare,
  KVGrpc,
  LeaseGrpc,
  PutRequest,
  RangeRequest,
  RequestOp,
  ResponseOp,
  TxnRequest,
  TxnResponse
}
import io.grpc.ManagedChannelBuilder
import scala.concurrent.duration.FiniteDuration

object EtcdCoordination {

  private implicit def byteStringToString(bytes: ByteString): String =
    bytes.toStringUtf8

  private implicit def stringToByteString(s: String): ByteString =
    ByteString.copyFromUtf8(s)
}

final class EtcdCoordination(clusterName: String, system: ActorSystem)
    extends CoordinationNew[Long] {

  import EtcdCoordination._
  import system.dispatcher

  private val prefix      = s"constructr/$clusterName"
  private val prefixSlash = s"$prefix/"

  private val channel = {
    val host = system.settings.config.getString("constructr.coordination.host")
    val port = system.settings.config.getInt("constructr.coordination.port")
    ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build()
  }
  private val kv    = KVGrpc.stub(channel)
  private val lease = LeaseGrpc.stub(channel)

  override def bootstrapOrNodes() = {
    val txnRequest = {
      val compare =
        Vector(
          Compare(CompareResult.GREATER,
                  CompareTarget.CREATE,
                  prefix,
                  TargetUnion.CreateRevision(0))
        )
      val success =
        Vector(
          RequestOp(
            RequestOp.Request.RequestRange(
              RangeRequest(prefixSlash, keysOnly = true)
            )
          )
        )
      val failure =
        Vector(
          RequestOp(RequestOp.Request.RequestPut(PutRequest(prefixSlash)))
        )
      TxnRequest(compare, success, failure)
    }
    kv.txn(txnRequest).map {
      case TxnResponse(_, true, responseOps) =>
        val keys =
          responseOps.flatMap {
            case ResponseOp(ResponseRange(rr)) => // Intentionally not exhaustive!
              rr.kvs.map(_.key)
          }
      case TxnResponse(_, false, responses) => ???
    }

    ???
  }

  override def addSelf(self: Address, ttl: FiniteDuration) = ???

  override def refresh(self: Address, ttl: FiniteDuration, a: Long) = ???
}
