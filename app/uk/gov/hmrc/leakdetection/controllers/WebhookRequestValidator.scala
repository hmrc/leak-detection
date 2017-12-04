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
import play.api.mvc.BodyParser
import play.api.mvc.Results._
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.leakdetection.model.PayloadDetails

object WebhookRequestValidator {

  val logger = Logger(this.getClass.getName)

  def parser(implicit ec: ExecutionContext): BodyParser[PayloadDetails] =
    BodyParser { _ =>
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)

      Accumulator(sink).map { bytes =>
        Either
          .catchNonFatal {
            Json.parse(bytes.utf8String).as[PayloadDetails]
          }
          .leftMap { ex =>
            val msg = s"Error parsing request, details: ${ex.getMessage}"
            logger.warn(msg)
            BadRequest(errorAsJson(msg))
          }
      }
    }

  private def errorAsJson(errorMsg: String): JsValue =
    Json.obj("error" -> errorMsg)

  private def isValidSignature(payload: String, ghSignature: String, secret: String): Boolean = {
    val algorithm  = "HmacSHA1"
    val secretSpec = new SecretKeySpec(secret.getBytes(), algorithm)
    val hmac       = Mac.getInstance(algorithm)

    hmac.init(secretSpec)

    val sig           = hmac.doFinal(payload.getBytes("UTF-8"))
    val hashOfPayload = s"sha1=${DatatypeConverter.printHexBinary(sig)}"

    println
    println("payload when verifying:")
    println(payload)
    println
    println(s"hashOfPayload $hashOfPayload")
    println(s"ghSignature $ghSignature")

    ghSignature.equalsIgnoreCase(hashOfPayload)
  }
}
