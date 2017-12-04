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
import play.api.libs.json.{JsValue, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.Results._
import play.api.mvc.{BodyParser, Headers}
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
        Either
          .catchNonFatal {
            validateAndParse(bytes, rh.headers, webhookSecret)
          }
          .leftMap { ex =>
            logger.warn(ex.getMessage)
            BadRequest(errorAsJson(ex.getMessage))
          }
      }
    }

  private def validateAndParse(bytes: ByteString, headers: Headers, webhookSecret: String) = {
    val payload = bytes.utf8String
    val signature =
      headers
        .get("X-Hub-Signature")
        .getOrElse(throw new Exception("Signature not found in headers"))

    if (isValidSignature(payload, signature, webhookSecret)) {
      Json.parse(payload).as[PayloadDetails]
    } else {
      throw new Exception("Invalid signature")
    }
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

  private def errorAsJson(errorMsg: String): JsValue =
    Json.obj("error" -> "Error parsing request", "details" -> errorMsg)
}
