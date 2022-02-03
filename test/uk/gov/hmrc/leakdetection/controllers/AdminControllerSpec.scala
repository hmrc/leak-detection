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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsNumber, JsValue}
import play.api.mvc.{Result, Results}
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.model.{Branch, Report, ReportId, Repository}
import uk.gov.hmrc.leakdetection.services.{LeaksService, ReportsService, ScanningService}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}


class AdminControllerSpec extends AnyWordSpec with Matchers with ArgumentMatchersSugar with ScalaFutures with MockitoSugar with Results {

  import play.api.test.Helpers._

  "validate" should {

    List("private" -> true, "public" -> false) foreach {
      case (repoType, isPrivate) =>
        s"scan the git $repoType repository and return an empty report" in new TestSetup {

          val now = Instant.EPOCH
          val id  = ReportId.random

          when(
            scanningService.scanRepository(
              Repository(eqTo("repoName")),
              Branch(eqTo("main")),
              eqTo(isPrivate),
              eqTo("https://github.com/hmrc/repoName"),
              eqTo("n/a"),
              eqTo("n/a"),
              eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
              eqTo(false)
            )(any))
            .thenReturn(
              Future.successful(Report(id, "repoName", "someUrl", "n/a", "main", now, "n/a", 0, Map.empty)))

          val result: Future[Result] = controller.validate(Repository("repoName"), Branch.main, isPrivate, None)(FakeRequest())
          val json: String = contentAsString(result)

          json shouldBe s"""{"_id":"$id","repoName":"repoName","repoUrl":"someUrl","commitId":"n/a","branch":"main","timestamp":"1970-01-01T00:00:00.000Z","author":"n/a","totalLeaks":0,"rulesViolated":{}}"""
        }

        s"scan the git $repoType repository and some report" in new TestSetup {

          val now = Instant.EPOCH
          val id  = ReportId.random

          when(
            scanningService.scanRepository(
              Repository(eqTo("repoName")),
              Branch(eqTo("main")),
              eqTo(isPrivate),
              eqTo("https://github.com/hmrc/repoName"),
              eqTo("n/a"),
              eqTo("n/a"),
              eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
              eqTo(false)
            )(any))
            .thenReturn(Future.successful(Report(
              id,
              "repoName",
              "someUrl",
              "n/a",
              "main",
              now,
              "n/a",
              1,
              Map("rule1" -> 1)
            )))

          val result        = controller.validate(Repository("repoName"), Branch.main, isPrivate, None)(FakeRequest())
          val json: JsValue = contentAsJson(result)

          (json \ "totalLeaks").get shouldBe JsNumber(1)
        }
    }
  }

  trait TestSetup {

    val configLoader    = mock[ConfigLoader]
    val scanningService = mock[ScanningService]
    val reportsService  = mock[ReportsService]
    val leaksService    = mock[LeaksService]
    val httpClient      = mock[HttpClient]

    val controller =
      new AdminController(configLoader, scanningService, leaksService, httpClient, stubControllerComponents())(ExecutionContext.global)
  }

}
