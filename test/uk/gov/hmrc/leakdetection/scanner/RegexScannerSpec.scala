/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.scanner

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption}

class RegexScannerSpec extends AnyWordSpec with Matchers {

  "scanning file content" should {
    "look for a regex in a given text and" should {
      "return line numbers for matches" in {
        val text1  = "this matches the regex"
        val descr  = "descr for regex"
        val ruleId = "rule-1"
        val rule   = Rule(ruleId, Rule.Scope.FILE_CONTENT, "(matches)", descr)

        RegexScanner(rule, Int.MaxValue).scanLine(text1, 7, "filepath", false, Seq()) shouldBe Some(
          MatchedResult(
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "this matches the regex",
            lineNumber  = 7,
            ruleId      = ruleId,
            description = descr,
            matches     = List(Match(start = 5, end = 12)),
            priority    = Rule.Priority.Low,
            filePath    = "filepath"
          )
        )
      }

      "return None if text doesn't have matching the given regex" in {
        val text   = "this is a test"
        val ruleId = "rule-1"

        val rule = Rule(ruleId, Rule.Scope.FILE_CONTENT, "(was)", "descr")

        RegexScanner(rule, Int.MaxValue).scanLine(text, 1, "filepath", false, Seq()) shouldBe None
      }
    }
    "respect max line length and truncate lineText" in {
      val text  = "abc AA def BB ghi CC xyz"
      val rule  = Rule("ruleId", Rule.Scope.FILE_CONTENT, "BB", "descr")
      val limit = 2

      val matchedResult = RegexScanner(rule, limit).scanLine(text, 1,"filepath", false, Seq()).get

      matchedResult.lineText shouldBe "[…] BB […]"
      matchedResult.matches  shouldBe List(Match(start = 4, end = 6))
    }
    "handling exclusions" should {
      "mark as excluded if inline flag is true" in {
        val text = "abc AA def BB ghi CC xyz"
        val rule = Rule("ruleId", Rule.Scope.FILE_CONTENT, "BB", "descr")
        val limit = 2

        val matchedResult = RegexScanner(rule, limit).scanLine(text, 1, "filepath", true, Seq()).get

        matchedResult.isExcluded shouldBe true
      }
      "mark as excluded if line matches rule exemptions text" in {
        val text = "abc AA def BB ghi CC xyz"
        val rule = Rule("ruleId", Rule.Scope.FILE_CONTENT, "BB", "descr")
        val limit = 2

        val matchedResult = RegexScanner(rule, limit).scanLine(text, 1, "filepath", false, Seq(
          RuleExemption("ruleId", Seq("filepath"), Some("xyz")))
        ).get

        matchedResult.isExcluded shouldBe true
      }
      "mark as excluded if file level rule exemption matches filename" in {
        val text = "abc AA def BB ghi CC xyz"
        val rule = Rule("ruleId", Rule.Scope.FILE_CONTENT, "BB", "descr")
        val limit = 2

        val matchedResult = RegexScanner(rule, limit).scanLine(text, 1, "filepath", false, Seq(
          RuleExemption("ruleId", Seq("filepath"), None))
        ).get

        matchedResult.isExcluded shouldBe true
      }
    }
  }

  "scanning file names" should {
    "return a result if regex found a problem" in {
      val fileName = "foo.key"
      val ruleId   = "rule-1"
      val descr    = "descr"
      val rule     = Rule(ruleId, Rule.Scope.FILE_NAME, """^.*\.key$""", descr)

      RegexScanner(rule, Int.MaxValue).scanFileName(fileName, "filepath", Seq()) shouldBe
        Some(
          MatchedResult(
            scope       = Rule.Scope.FILE_NAME,
            lineText    = fileName,
            lineNumber  = 1,
            ruleId      = ruleId,
            description = descr,
            matches     = List(Match(start = 0, end = 7)),
            priority    = Rule.Priority.Low,
            filePath    = "filepath"
          ))
    }
    "return nothing if no match was found" in {
      val fileName = "foo.key"
      val rule     = Rule("rule-id", Rule.Scope.FILE_NAME, "doesn't match", "descr")

      RegexScanner(rule, Int.MaxValue).scanFileName(fileName, "filepath", Seq()) shouldBe None
    }
    "mark as excluded if rule exemptions exist for file and rule" in {
      val fileName = "foo.key"
      val ruleId   = "rule-1"
      val descr    = "descr"
      val rule     = Rule(ruleId, Rule.Scope.FILE_NAME, """^.*\.key$""", descr)

      val matchedResult = RegexScanner(rule, Int.MaxValue).scanFileName(fileName, "/filepath/foo.key", Seq(RuleExemption("rule-1", Seq("/filepath/foo.key"), None)))

      matchedResult.map(_.isExcluded) shouldBe Some(true)
    }
  }
}
