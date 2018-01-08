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

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Matchers.{any, eq => is}
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.JsValue
import play.api.mvc.Results
import play.api.test.FakeRequest
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.model.{Report, ReportId, ReportLine}
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.leakdetection.services.{ReportsService, ScanningService}
import scala.concurrent.{ExecutionContext, Future}

class AdminControllerSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar with Results {

  import play.api.test.Helpers._

  "validatePrivate" should {

    "scan the git repository and return an empty report" in new TestSetup {

      val now = new DateTime(0, DateTimeZone.UTC)
      val id  = ReportId.random

      when(
        scanningService.scanRepository(
          is("repoName"),
          is("master"),
          is(true),
          is("https://github.com/hmrc/repoName"),
          is("NA"),
          is("NA"),
          is("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}")
        )(any[ExecutionContext]))
        .thenReturn(Future.successful(Report(id, "repoName", "someUrl", "NA", now, "NA", Seq.empty)))

      val result       = controller.validatePrivate("repoName", "master")(FakeRequest())
      val json: String = contentAsString(result)

      json shouldBe s"""{"_id":"$id","repoName":"repoName","repoUrl":"someUrl","commitId":"NA","timestamp":"1970-01-01T00:00:00.000Z","author":"NA","inspectionResults":[]}"""
    }

    "scan the git repository and some report" in new TestSetup {

      val now = new DateTime(0, DateTimeZone.UTC)
      val id  = ReportId.random

      when(
        scanningService.scanRepository(
          is("repoName"),
          is("master"),
          is(true),
          is("https://github.com/hmrc/repoName"),
          is("NA"),
          is("NA"),
          is("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}")
        )(any[ExecutionContext]))
        .thenReturn(Future.successful(Report(
          id,
          "repoName",
          "someUrl",
          "NA",
          now,
          "NA",
          inspectionResults = Seq(
            ReportLine(
              "/some-file",
              Rule.Scope.FILE_CONTENT,
              1,
              "some url",
              "a description",
              "the line",
              List(Match(0, 1, "line")))
          )
        )))

      val result        = controller.validatePrivate("repoName", "master")(FakeRequest())
      val json: JsValue = contentAsJson(result)

      (json \ "inspectionResults").get.toString shouldBe s"""[{"filePath":"/some-file","scope":"${Rule.Scope.FILE_CONTENT}","lineNumber":1,"urlToSource":"some url","description":"a description","lineText":"the line","matches":[{"start":0,"end":1,"value":"line"}]}]"""
    }
  }

  trait TestSetup {

    val configLoader    = mock[ConfigLoader]
    val scanningService = mock[ScanningService]
    val reportService   = mock[ReportsService]

    val controller = new AdminController(configLoader, scanningService, reportService)
  }

}
