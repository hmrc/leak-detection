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
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.mongo.test.MongoSupport

import java.io.PrintWriter
import java.nio.file.Files
import java.time.Instant

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
      when(repoVisibilityChecker.checkVisibilityDefinedCorrectly(dir, true)).thenReturn(Some(MissingRepositoryYamlFile))

      val results = warningsService.getWarnings(aReport, dir, true)

      results shouldBe Seq(Warning("repoName", "branch", timestamp, ReportId("report"), MissingRepositoryYamlFile.toString))
    }

    "return no warnings if visibility checks passed" in new TestSetup {
      when(repoVisibilityChecker.checkVisibilityDefinedCorrectly(dir, true)).thenReturn(None)

      val results = warningsService.getWarnings(aReport, dir, true)

      results shouldBe Seq.empty
    }

    "return file level exemption warning if missing text element against rules with scope FileContent" in new TestSetup {
      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-1'
           |    filePath: 'file'
        """.stripMargin
      }

      val results = warningsService.getWarnings(aReport, dir, true)

      results shouldBe Seq(Warning("repoName", "branch", timestamp, ReportId("report"), FileLevelExemptions.toString))
    }

    "not return file level exemption warning if missing text element against rules with scope FileName" in new TestSetup {
      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-2'
           |    filePath: 'file'
        """.stripMargin
      }

      val results = warningsService.getWarnings(aReport, dir, true)

      results shouldBe Seq.empty
    }

    "not return file level exemption warning if all exemptions are line level exemptions" in new TestSetup {
      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-1'
           |    filePath: 'file'
           |    text: 'false-positive'
        """.stripMargin
      }

      val results = warningsService.getWarnings(aReport, dir, true)

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

    val timestamp = Instant.now()

    def aReport = Report(ReportId("report"), "repoName", "url", "commit", "branch", timestamp, "author", 0, Map.empty)

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

    lazy val config =
      Cfg(
        allRules = AllRules(Nil, List(fileContentRule, fileNameRule)),
        githubSecrets = GithubSecrets("", ""),
        maxLineLength = Int.MaxValue,
        clearingCollectionEnabled = false,
        github = Github("", "")
      )

    lazy val configLoader = new ConfigLoader {
      val cfg = config
    }

    val repoVisibilityChecker = mock[RepoVisibilityChecker]
    when(repoVisibilityChecker.checkVisibilityDefinedCorrectly(any, any)).thenReturn(None)

    val warningsService = new WarningsService(configLoader, repoVisibilityChecker)
  }
}
