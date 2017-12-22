/*
 * Copyright 2017 HM Revenue & Customs
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import play.api.Logger
import play.api.libs.json.{Format, JsError, JsSuccess, JsValue, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.Results._
import play.api.mvc.{BodyParser, Headers, Result}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

object WebhookRequestValidator {

  val logger = Logger(this.getClass.getName)

  def parser(webhookSecret: String): BodyParser[PayloadDetails] =
    BodyParser { rh =>
      implicit val hc: HeaderCarrier =
        HeaderCarrierConverter.fromHeadersAndSession(rh.headers)

      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      Accumulator(sink).map { bytes =>
        getPayloadDetails(bytes, rh.headers, webhookSecret)
      }
    }

  private def getPayloadDetails(
    bytes: ByteString,
    headers: Headers,
    webhookSecret: String): Either[Result, PayloadDetails] = {
    val payload = bytes.utf8String
    val signature: Either[ValidationError, String] =
      Either.fromOption(headers.get("X-Hub-Signature"), ifNone = SignatureNotFound)

    signature
      .flatMap { s =>
        if (isValidSignature(payload, s, webhookSecret)) {
          Json.parse(payload).validate[PayloadDetails] match {
            case JsSuccess(pd, _) => checkIfEventShouldBeProcessed(pd)
            case JsError(_)       => ignoreIfZenMessage(payload).asLeft
          }
        } else {
          InvalidSignature.asLeft
        }
      }
      .leftMap {
        case DeleteBranchEvent  => Ok(Json.toJson(Message("Events related to deleting branches are ignored")))
        case ZenMessage         => Ok(Json.toJson(Message("Zen message ignored")))
        case e: ValidationError => BadRequest(errorAsJson(e.toString))
      }
  }

  final case class ZenPayload(zen: String)
  object ZenPayload {
    implicit val format: Format[ZenPayload] = Json.format[ZenPayload]
  }

  def ignoreIfZenMessage(payload: String): ValidationError =
    Json.parse(payload).validate[ZenPayload] match {
      case JsSuccess(_, _) => ZenMessage
      case JsError(errors) => InvalidPayload(errors.toString)
    }

  def checkIfEventShouldBeProcessed(pd: PayloadDetails): Either[ValidationError, PayloadDetails] =
    ignoreIfDeleteBranchEvent(pd)

  def ignoreIfDeleteBranchEvent(pd: PayloadDetails): Either[ValidationError, PayloadDetails] =
    if (pd.deleted) {
      DeleteBranchEvent.asLeft
    } else {
      pd.asRight
    }

  def isValidSignature(payload: String, ghSignature: String, secret: String): Boolean = {
    val algorithm  = "HmacSHA1"
    val secretSpec = new SecretKeySpec(secret.getBytes(), algorithm)
    val hmac       = Mac.getInstance(algorithm)

    hmac.init(secretSpec)

    val sig           = hmac.doFinal(payload.getBytes("UTF-8"))
    val hashOfPayload = s"sha1=${DatatypeConverter.printHexBinary(sig)}"

    ghSignature.equalsIgnoreCase(hashOfPayload)
  }

  final case class Message(details: String)
  object Message {
    implicit val format: Format[Message] = Json.format[Message]
  }

  private def errorAsJson(errorMsg: String): JsValue =
    Json.obj("error" -> "Error parsing request", "details" -> errorMsg)

  sealed trait ValidationError
  case object SignatureNotFound extends ValidationError
  case object InvalidSignature extends ValidationError
  case object ZenMessage extends ValidationError
  case object DeleteBranchEvent extends ValidationError
  final case class InvalidPayload(errors: String) extends ValidationError

}
