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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.scalatest.{Matchers, WordSpec}
import org.scalatestplus.play.OneAppPerSuite
import play.api.test.{FakeRequest, Helpers}
import concurrent.duration._
import ModelFactory._
import play.api.libs.json.Json

class WebhookControllerSpec extends WordSpec with Matchers with OneAppPerSuite {

  implicit val system: ActorSystem    = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  "Github Webhook" should {
    "work e2e" in {

      implicit val timeout = Timeout(5.seconds)

      val input = jsonify(aPayloadDetails)

      println
      println(Json.prettyPrint(Json.parse(input)))
      println

      println
      println(s"input was = $input")
      println

      val req =
        FakeRequest("POST", "/leak-detection/validate")
          .withBody(input)

      val res = Helpers.route(app, req).get

      println("-----------------------")
      println(Helpers.contentAsString(res))
      println("-----------------------")

      Helpers.status(res) shouldBe 200

    }
  }
}
