/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.controllers

import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.implicits._
import org.apache.commons.codec.digest.HmacAlgorithms

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.xml.bind.DatatypeConverter
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.streams.Accumulator
import play.api.mvc.Results._
import play.api.mvc.{BodyParser, Headers, Result}
import uk.gov.hmrc.leakdetection.model.{DeleteBranchEvent, GithubRequest, PayloadDetails, RepositoryEvent, ZenMessage}

import scala.concurrent.ExecutionContext

class WebhookRequestValidator @Inject()(implicit ec: ExecutionContext) {

  def parser(webhookSecret: String): BodyParser[GithubRequest] =
    BodyParser { rh =>
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)

      Accumulator(sink).map { bytes =>
        withValidSignature(bytes, rh.headers, webhookSecret) { payload =>
          Json.parse(payload) match {
            case ExtractPayloadDetails(p)    => p
            case ExtractDeleteBranchEvent(d) => d
            case ExtractZenMessage(z)        => z
            case ExtractRepositoryEvent(r)   => r
          }
        }
      }
    }

  def withValidSignature(payload: ByteString, headers: Headers, webhookSecret: String)(
    f: String => GithubRequest): Either[Result, GithubRequest] =
    headers
      .get("X-Hub-Signature-256")
      .map { signature =>
        val payloadAsString = payload.utf8String
        if (isValidSignature(payloadAsString, signature, webhookSecret))
          f(payloadAsString).asRight
        else
          BadRequest(errorParsingRequest("Invalid Signature")).asLeft
      }
      .getOrElse(
        BadRequest(errorParsingRequest("Signature not found in headers")).asLeft
      )

  def isValidSignature(payload: String, ghSignature: String, secret: String): Boolean = {
    val algorithm  = HmacAlgorithms.HMAC_SHA_256.toString
    val secretSpec = new SecretKeySpec(secret.getBytes(), algorithm)
    val hmac       = Mac.getInstance(algorithm)

    hmac.init(secretSpec)

    val sig           = hmac.doFinal(payload.getBytes("UTF-8"))
    val hashOfPayload = s"sha256=${DatatypeConverter.printHexBinary(sig)}"

    ghSignature.equalsIgnoreCase(hashOfPayload)
  }

  private def errorParsingRequest(errorMsg: String): JsValue =
    Json.obj("error" -> "Error parsing request", "details" -> errorMsg)

  class Extract[T](reads: Reads[T]) {
    def unapply(json: JsValue): Option[T] =
       reads.reads(json).asOpt
  }

  val ExtractPayloadDetails    = new Extract[PayloadDetails   ](PayloadDetails.githubReads)
  val ExtractZenMessage        = new Extract[ZenMessage       ](ZenMessage.githubReads)
  val ExtractDeleteBranchEvent = new Extract[DeleteBranchEvent](DeleteBranchEvent.githubReads)
  val ExtractRepositoryEvent = new Extract[RepositoryEvent](RepositoryEvent.githubReads)

}
