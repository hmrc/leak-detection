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

package uk.gov.hmrc.leakdetection.services

import ammonite.ops.Path
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.ModelFactory.aSlackConfig
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.WarningRepository
import uk.gov.hmrc.mongo.test.MongoSupport

import java.io.PrintWriter
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global

class WarningServiceSpec
  extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with ArgumentMatchersSugar
    with MongoSupport
    with IntegrationPatience {

  "warning service" should {
    "return warning if visibility check identified an issue" in new TestSetup {
      when(repoVisibilityChecker.checkVisibility(dir, true, false)).thenReturn(Some(MissingRepositoryYamlFile))

      val results = warningsService.checkForWarnings(aReport, dir, true, false, List.empty, Seq.empty)

      results shouldBe Seq(Warning("repoName", "branch", timestamp, ReportId("report"), MissingRepositoryYamlFile.toString))
    }

    "return no warnings if visibility checks passed" in new TestSetup {
      when(repoVisibilityChecker.checkVisibility(dir, true, false)).thenReturn(None)

      val results = warningsService.checkForWarnings(aReport, dir, true, false, List.empty, Seq.empty)

      results shouldBe Seq.empty
    }

    "return file level exemption warning if missing text element against rules with scope FileContent" in new TestSetup {
      val exemptions = List(RuleExemption("rule-1", Seq("file")))

      val results = warningsService.checkForWarnings(aReport, dir, true, false, exemptions, Seq.empty)

      results shouldBe Seq(Warning("repoName", "branch", timestamp, ReportId("report"), FileLevelExemptions.toString))
    }

    "include file level exemption warnings if repository is archived" in new TestSetup {
      val exemptions = List(RuleExemption("rule-1", Seq("file")))

      val results = warningsService.checkForWarnings(aReport, dir, true, true, exemptions, Seq.empty)

      results shouldBe Seq(Warning("repoName", "branch", timestamp, ReportId("report"), FileLevelExemptions.toString))
    }

    "not return file level exemption warning if missing text element against rules with scope FileName" in new TestSetup {
      val exemptions = List(RuleExemption("rule-2", Seq("file")))

      val results = warningsService.checkForWarnings(aReport, dir, true, false, exemptions, Seq.empty)

      results shouldBe Seq.empty
    }

    "not return file level exemption warning if all exemptions are line level exemptions" in new TestSetup {

      val exemptions = List(RuleExemption("rule-1", Seq("file"), Some("false-positive")))

      val results = warningsService.checkForWarnings(aReport, dir, true, false, exemptions, Seq.empty)

      results shouldBe Seq.empty
    }

    "return unused exemptions warning if report has unused exemptions" in new TestSetup {
      val exemptions = List(RuleExemption("rule-1", Seq("/dir/file1"), Some("some text")))

      val report = aReport.copy(unusedExemptions = Seq(UnusedExemption("rule-1", "/dir/file1", Some("some text"))))

      val results = warningsService.checkForWarnings(report, dir, true, false, exemptions, Seq.empty)

      results shouldBe Seq(Warning("repoName", "branch", timestamp, ReportId("report"), UnusedExemptions.toString))
    }

    "ignore unused exemptions warning if repository is archived" in new TestSetup {
      val exemptions = List(RuleExemption("rule-1", Seq("/dir/file1"), Some("some text")))

      val report = aReport.copy(unusedExemptions = Seq(UnusedExemption("rule-1", "/dir/file1", Some("some text"))))

      val results = warningsService.checkForWarnings(report, dir, true, true, exemptions, Seq.empty)

      results shouldBe Seq.empty
    }
  }

  trait TestSetup {

    val unzippedTmpDirectory = Files.createTempDirectory("unzipped_")
    val projectDirectory = Files.createTempDirectory(unzippedTmpDirectory, "repoName")
    val dir = unzippedTmpDirectory.toFile

    def writeRepositoryYaml(contents: String): Unit = {
      val projectConfigurationYaml = Files.createFile(Path(s"$projectDirectory/repository.yaml").toNIO).toFile
      new PrintWriter(projectConfigurationYaml) {
        write(contents);
        close()
      }
    }

    val timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    def aReport = Report(ReportId("report"), "repoName", "url", "commit", "branch", timestamp, "author", 0, 0, Map.empty, Map.empty, Seq.empty)

    val fileContentRule =
      Rule(
        id = "rule-1",
        scope = "fileContent",
        regex = """((?:play\.crypto\.secret(?!\s*(:|=)*\s*ENC\[)).*)""",
        description = "Unencrypted play.crypto.secret"
      )

    val fileNameRule =
      Rule(
        id = "rule-2",
        scope = "fileName",
        regex = "id_rsa",
        description = "checks-in private key!"
      )

    lazy val appConfig =
      AppConfig(
        allRules                  = AllRules(Nil, List(fileContentRule, fileNameRule)),
        githubSecrets             = GithubSecrets("", ""),
        maxLineLength             = Int.MaxValue,
        clearingCollectionEnabled = false,
        warningMessages           = Map.empty,
        alerts                    = Alerts(aSlackConfig)
      )

    val repoVisibilityChecker = mock[RepoVisibilityChecker]
    when(repoVisibilityChecker.checkVisibility(any, any, any)).thenReturn(None)

    val warningRepository = mock[WarningRepository]

    val warningsService = new WarningsService(appConfig, repoVisibilityChecker, warningRepository)
  }
}
