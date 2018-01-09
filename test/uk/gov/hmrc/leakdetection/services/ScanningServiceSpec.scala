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
import uk.gov.hmrc.leakdetection.scanner.{FileAndDirectoryUtils, Match, RegexMatchingEngine}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ScanningServiceSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar with Results {

  "scanRepository" should {

    "scan the git repository and return a report with found violations" in new TestSetup {

      val now = new DateTime(0, DateTimeZone.UTC)
      val id  = ReportId.random

      when(
        artifactService.getZipAndExplode(
          is(githubSecrets.personalAccessToken),
          is("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
          is("master"))).thenReturn(unzippedTmpDirectory.toFile)

      val report = scanningService
        .scanRepository(
          "repoName",
          "master",
          true,
          "https://github.com/hmrc/repoName",
          "some commit id",
          "me",
          "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}")
        .futureValue

      report.author   shouldBe "me"
      report.repoName shouldBe "repoName"
      report.commitId shouldBe "some commit id"
      report.repoUrl  shouldBe "https://github.com/hmrc/repoName"
      report.inspectionResults shouldBe
        Seq(
          ReportLine(
            s"/${file1.getName}",
            Rule.Scope.FILE_CONTENT,
            2,
            s"https://github.com/hmrc/repoName/blame/master/${file1.getName}#L2",
            "uses nulls!",
            " var x = null",
            List(Match(9, 13, "null"))
          ))

    }

    "scan a git repository and don't include exempted violations" in new TestSetup {
      val now = new DateTime(0, DateTimeZone.UTC)
      val id  = ReportId.random

      override val config = {

        def relativePath(file: File) =
          getFilePathRelativeToProjectRoot(explodedZipDir = unzippedTmpDirectory.toFile, file)

        val pathOfFile1 = relativePath(file1)
        val pathOfFile2 = relativePath(file2)

        Cfg(
          AllRules(publicRules = Nil, privateRules = List(rules.usesNulls, rules.checksInPrivateKeys)),
          githubSecrets,
          allRuleExemptions =
            AllRuleExemptions(List(RuleExemption("rule-1", pathOfFile1), RuleExemption("rule-2", pathOfFile2)))
        )
      }

      when(
        artifactService.getZipAndExplode(
          is("pat"),
          is("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
          is("master"))).thenReturn(unzippedTmpDirectory.toFile)

      val report = scanningService
        .scanRepository(
          "repoName",
          "master",
          true,
          "https://github.com/hmrc/repoName",
          "some commit id",
          "me",
          "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}")
        .futureValue

      report.inspectionResults shouldBe Nil
    }

  }

  trait TestSetup {

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

      val checksInPrivateKeys =
        Rule(
          id          = "rule-2",
          scope       = "fileName",
          regex       = "id_rsa",
          description = "checks-in private key!"
        )

    }

    val allRules = AllRules(
      publicRules  = Nil,
      privateRules = List(rules.usesNulls)
    )

    val config = Cfg(
      allRules          = allRules,
      githubSecrets     = githubSecrets,
      allRuleExemptions = AllRuleExemptions(Nil)
    )

    lazy val configLoader = new ConfigLoader {
      val cfg = config
    }

    val artifactService = mock[ArtifactService]

    val reportRepository = mock[ReportsRepository]
    when(reportRepository.saveReport(any())).thenAnswer(new Answer[Future[Report]] {
      override def answer(invocation: InvocationOnMock): Future[Report] =
        Future(invocation.getArgumentAt(0, classOf[Report]))
    })

    val unzippedTmpDirectory = Files.createTempDirectory("unzipped_")
    val projectDirectory     = Files.createTempDirectory(unzippedTmpDirectory, "repoName")
    val file1                = Files.createTempFile(projectDirectory, "test1", ".txt").toFile
    val file2                = Files.createTempFile(projectDirectory, "test2", "id_rsa").toFile
    new PrintWriter(file1) {
      write("package foo \n var x = null"); close()
    }

    lazy val scanningService =
      new ScanningService(artifactService, new RegexMatchingEngine(), configLoader, reportRepository)
  }

}
