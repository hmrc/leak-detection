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

package uk.gov.hmrc.leakdetection.model
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import uk.gov.hmrc.leakdetection.config.Rule
import uk.gov.hmrc.leakdetection.controllers.AdminController
import uk.gov.hmrc.leakdetection.scanner.{Match, MatchedResult, Result}

class ReportLineSpec extends AnyFreeSpec with Matchers with OptionValues {

  "ReportLine" - {
    "when creating directly" - {
      "should set the url to the correct line of the file" in {
        val repoUrl   = "http://githib.com/some-special-repo/"
        val commitId  = "3c81a24"
        val urlToFile = "/src/main/scala/SomeClass.scala"

        val descr      = "some descr"
        val ruleId     = "rule-1"
        val lineNumber = 95

        val reportLine =
          ReportLine.build(
            repoUrl,
            commitId,
            Result(
              urlToFile,
              MatchedResult(
                scope       = Rule.Scope.FILE_CONTENT,
                lineText    = "some matched text in the file",
                lineNumber  = lineNumber,
                ruleId      = ruleId,
                description = descr,
                matches     = List(Match(start = 6, end = 12)),
                priority    = Rule.Priority.Low
              )
            )
          )

        reportLine.urlToSource  shouldBe s"$repoUrl/blame/3c81a24$urlToFile#L$lineNumber"
        reportLine.description  shouldBe descr
        reportLine.ruleId.value shouldBe ruleId

      }

      "should set the url to the correct line of the file when the branch is without refs/heads" in {
        val repoUrl   = "http://githib.com/some-special-repo/"
        val commitId  = "3c81a24"
        val urlToFile = "/src/main/scala/SomeClass.scala"

        val descr      = "some descr"
        val ruleId     = "rule-1"
        val lineNumber = 95

        val reportLine =
          ReportLine.build(
            repoUrl,
            commitId,
            Result(
              urlToFile,
              MatchedResult(
                scope       = Rule.Scope.FILE_CONTENT,
                lineText    = "some matched text in the file",
                lineNumber  = lineNumber,
                ruleId      = ruleId,
                description = descr,
                matches     = List(Match(start = 6, end = 12)),
                priority    = Rule.Priority.Low
              )
            )
          )

        reportLine.urlToSource  shouldBe s"$repoUrl/blame/3c81a24$urlToFile#L$lineNumber"
        reportLine.description  shouldBe descr
        reportLine.ruleId.value shouldBe ruleId

      }
    }

    "when creating from reports" - {
      val branch     = "main"
      val repoUrl    = "url"
      val urlToFile  = "/filePath"
      val lineNumber = 1
      def createReport(commitId: String): Report =
        Report.create(
          repositoryName = "repoName",
          repositoryUrl  = repoUrl,
          commitId       = commitId,
          authorName     = "author",
          branch         = branch,
          results = List(
            Result(
              filePath = urlToFile,
              scanResults = MatchedResult(
                scope       = "scope",
                lineText    = "lineText",
                lineNumber  = lineNumber,
                description = "descr",
                ruleId      = "ruleId",
                matches     = List(Match(1, 2)),
                priority    = Rule.Priority.Low
              )
            ))
        )

      "should use commit id instead of branch" in {
        val commitId = "4e4a52c"
        createReport(commitId).inspectionResults.head.urlToSource shouldBe
          s"$repoUrl/blame/$commitId$urlToFile#L$lineNumber"
      }

      "should use branch name if commitId = n/a (indicates manual scan from admin endpoints)" in {
        val `n/a commitId` = AdminController.NOT_APPLICABLE
        createReport(`n/a commitId`).inspectionResults.head.urlToSource shouldBe
          s"$repoUrl/blame/$branch$urlToFile#L$lineNumber"
      }
    }
  }

}
