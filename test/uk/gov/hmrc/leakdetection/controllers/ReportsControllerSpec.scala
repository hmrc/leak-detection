/*
 * Copyright 2021 HM Revenue & Customs
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

import akka.util.Timeout
import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import concurrent.duration._
import play.api.libs.json.Json
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.leakdetection.services.ReportsService

class ReportsControllerSpec extends AnyWordSpec with Matchers with MockitoSugar {



  "Reports list" should {

    "be in json if 'application/json' Accept header was sent" in {
      val mockedReportsService = mock[ReportsService]
      val controller           = new ReportsController(null, mockedReportsService, stubControllerComponents())
      val request              = FakeRequest().withHeaders("Accept" -> "application/json")
      val repos                = List("repo1", "repo2")
      when(mockedReportsService.getRepositories).thenReturn(Future(repos))

      val result = controller.repositories(request)

      Helpers.status(result)        shouldBe 200
      Helpers.contentType(result)   shouldBe Some("application/json")
      Helpers.contentAsJson(result) shouldBe Json.toJson(repos)
    }

    "be in html if appropriate '*/*' Accept header was sent" in {
      val mockedReportsService = mock[ReportsService]
      val controller           = new ReportsController(null, mockedReportsService, stubControllerComponents())
      val request              = FakeRequest().withHeaders("Accept" -> "*/*")
      val repos                = List("repo1", "repo2")
      when(mockedReportsService.getRepositories).thenReturn(Future(repos))

      val result = controller.repositories(request)

      Helpers.status(result)      shouldBe 200
      Helpers.contentType(result) shouldBe Some("text/html")
    }
  }
}
