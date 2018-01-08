/*
 * Copyright 2018 HM Revenue & Customs
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

import uk.gov.hmrc.leakdetection.ModelFactory._
import org.apache.commons.codec.digest.HmacUtils
import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.Json
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import WebhookRequestValidator.isValidSignature

class WebhookRequestValidatorSpec extends WordSpec with Matchers {

  s"Parsing ${PayloadDetails.getClass.getName}" should {
    "succeed if all required fields are present" in {
      val expectedPayloadDetails = aPayloadDetails
      val validJson              = asJson(expectedPayloadDetails)

      val res = Json.parse(validJson).as[PayloadDetails](PayloadDetails.reads)

      res shouldBe expectedPayloadDetails
    }
  }

  "Validating payload signature" should {
    "fail if signature invalid" in {
      val secret           = aString()
      val payload          = aString()
      val invalidSignature = aString()

      val res = isValidSignature(payload, invalidSignature, secret)

      withClue("Expected a failure for invalid signature") {
        res shouldBe false
      }
    }
    "succeed if valid" in {
      val secret         = aString()
      val payload        = aString()
      val validSignature = "sha1=" + HmacUtils.hmacSha1Hex(secret, payload)

      val res = isValidSignature(payload, validSignature, secret)

      withClue("Expected signature validation to succeed") {
        res shouldBe true
      }
    }
  }

}
