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

package uk.gov.hmrc.leakdetection.services

import java.io.{File, PrintWriter}
import java.nio.file.Files

import ammonite.ops.Path
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Matchers.{any, eq => is}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import play.api.mvc.Results
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.model.{Report, ReportId, ReportLine}
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.leakdetection.scanner.FileAndDirectoryUtils._
import uk.gov.hmrc.leakdetection.scanner.{Match, RegexMatchingEngine}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ScanningServiceSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar with Results {

  "scanRepository" should {

    "scan the git repository and return a report with found violations" in new TestSetup {

      override val privateRules = List(rules.usesNulls, rules.checksInPrivateKeys)

      val startIndex = file2.getName.indexOf("id_rsa")

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "some commit id"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.inspectionResults should contain theSameElementsAs
        Seq(
          ReportLine(
            s"/${file1.getName}",
            Rule.Scope.FILE_CONTENT,
            2,
            s"https://github.com/hmrc/repoName/blame/master/${file1.getName}#L2",
            "uses nulls!",
            " var x = null",
            List(Match(9, 13, "null"))
          ),
          ReportLine(
            s"/${file2.getName}",
            Rule.Scope.FILE_NAME,
            1,
            s"https://github.com/hmrc/repoName/blame/master/${file2.getName}#L1",
            "checks-in private key!",
            s"${file2.getName}",
            List(Match(startIndex, startIndex + 6, "id_rsa"))
          )
        )

    }

    "scan a git repository and ignore a rule with the filename included in the ignoredFiles property" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeysExempted, rules.usesNullExempted)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "some commit id"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.inspectionResults shouldBe Nil
    }

    "scan a git repository and ignore a rule in multiple files included in the ignoredFiles property" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeysExempted, rules.usesUnencryptedKey)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "some commit id"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.inspectionResults shouldBe Nil
    }

    "scan a git repository and don't include project specific exempted violations" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeys)
      override lazy val projectConfigurationYamlContent: String =
        s"""
          |leakDetectionExemptions:
          |  - ruleId: 'rule-2'
          |    filePath: ${relativePath(file2)}
        """.stripMargin

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "some commit id"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.inspectionResults shouldBe Nil
    }

  }

  trait TestSetup {

    val now = new DateTime(0, DateTimeZone.UTC)
    val id  = ReportId.random

    def generateReport() =
      scanningService
        .scanRepository(
          "repoName",
          "master",
          true,
          "https://github.com/hmrc/repoName",
          "some commit id",
          "me",
          "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}")
        .futureValue

    val githubSecrets =
      GithubSecrets(
        personalAccessToken = "pat",
        webhookSecretKey    = "a secret"
      )

    object rules {
      val usesNulls =
        Rule(
          id          = "rule-1",
          scope       = "fileContent",
          regex       = "null",
          description = "uses nulls!"
        )

      val usesNullExempted =
        Rule(
          id           = "rule-1",
          scope        = "fileContent",
          regex        = "null",
          description  = "uses nulls!",
          ignoredFiles = List(relativePath(file1))
        )

      val checksInPrivateKeys =
        Rule(
          id          = "rule-2",
          scope       = "fileName",
          regex       = "id_rsa",
          description = "checks-in private key!"
        )

      val checksInPrivateKeysExempted =
        Rule(
          id           = "rule-2",
          scope        = "fileName",
          regex        = "id_rsa",
          description  = "checks-in private key!",
          ignoredFiles = List(relativePath(file2))
        )

      val usesUnencryptedKey =
        Rule(
          id           = "rule-3",
          scope        = "fileContent",
          regex        = """((?:play\.crypto\.secret(?!\s*(:|=)*\s*ENC\[)).*)""",
          description  = "Unencrypted play.crypto.secret",
          ignoredFiles = List("/application.conf", "/test-application.conf")
        )
    }

    def relativePath(file: File) =
      getFilePathRelativeToProjectRoot(explodedZipDir = unzippedTmpDirectory.toFile, file)

    val privateRules: List[Rule] = Nil

    lazy val config = Cfg(
      allRules      = AllRules(Nil, privateRules),
      githubSecrets = githubSecrets
    )

    lazy val configLoader = new ConfigLoader {
      val cfg = config
    }

    val artifactService  = mock[ArtifactService]
    val reportRepository = mock[ReportsRepository]

    val unzippedTmpDirectory = Files.createTempDirectory("unzipped_")
    val projectDirectory     = Files.createTempDirectory(unzippedTmpDirectory, "repoName")

    val applicationConf = Files.createFile(Path(s"$projectDirectory/application.conf").toNIO).toFile
    write("""play.crypto.secret="Htt5cyxh8"""", applicationConf)

    val testApplicationConf = Files.createFile(Path(s"$projectDirectory/test-application.conf").toNIO).toFile
    write("""play.crypto.secret="Htt5cyxh8"""", testApplicationConf)

    val file1 = Files.createTempFile(projectDirectory, "test1", ".txt").toFile
    write("package foo \n var x = null", file1)

    val file2 = Files.createTempFile(projectDirectory, "test2", "id_rsa").toFile

    val projectConfigurationYaml             = Files.createFile(Path(s"$projectDirectory/repository.yaml").toNIO).toFile
    lazy val projectConfigurationYamlContent = ""
    write(projectConfigurationYamlContent, projectConfigurationYaml)

    when(reportRepository.saveReport(any())).thenAnswer(new Answer[Future[Report]] {
      override def answer(invocation: InvocationOnMock): Future[Report] =
        Future(invocation.getArgumentAt(0, classOf[Report]))
    })

    when(
      artifactService.getZipAndExplode(
        is(githubSecrets.personalAccessToken),
        is("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
        is("master"))).thenReturn(unzippedTmpDirectory.toFile)

    lazy val scanningService =
      new ScanningService(artifactService, new RegexMatchingEngine(), configLoader, reportRepository)
  }

  def write(content: String, destination: File) =
    new PrintWriter(destination) {
      write(content); close()
    }
}
