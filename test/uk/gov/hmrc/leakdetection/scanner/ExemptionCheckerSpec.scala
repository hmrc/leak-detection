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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Priority
import uk.gov.hmrc.leakdetection.config.RuleExemption
import uk.gov.hmrc.leakdetection.model.UnusedExemption

class ExemptionCheckerSpec extends AnyWordSpec with Matchers {
  val exemptionChecker = new ExemptionChecker

  val aMatchedResult: MatchedResult = MatchedResult("", "", "", 0, "", "", List(), Priority.Low)

  "exemption checker" should {
    "return empty list if no exemptions defined" in {
      val results = exemptionChecker.run(Seq.empty, List.empty)

      results shouldBe empty
    }
    "return empty list if all exemptions cover an excluded match" in {
      val exemptions = Seq(
        RuleExemption("rule-1", Seq("/dir/file1"), Some("some text")),
        RuleExemption("rule-2", Seq("/dir/file2", "/dir/file3"), None)
      )

      val matchedResults = Seq(
        aMatchedResult.copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "with some text that matches", isExcluded = true),
        aMatchedResult.copy(ruleId = "rule-2", filePath = "/dir/file2", isExcluded = true),
        aMatchedResult.copy(ruleId = "rule-2", filePath = "/dir/file3", isExcluded = true)
      )

      val results = exemptionChecker.run(matchedResults, exemptions)

      results shouldBe empty
    }
    "return all exemptions as unused when no excluded results" in {
      val exemptions = Seq(
        RuleExemption("rule-1", Seq("/dir/file1"), Some("some text")),
        RuleExemption("rule-2", Seq("/dir/file2", "/dir/file3"), None)
      )

      val matchedResults = Seq(
        aMatchedResult.copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "some other text", isExcluded = false),
        aMatchedResult.copy(ruleId = "rule-3", filePath = "/dir/file1", isExcluded = false)
      )

      val results = exemptionChecker.run(matchedResults, exemptions)

      results shouldBe Seq(
        UnusedExemption("rule-1", "/dir/file1", Some("some text")),
        UnusedExemption("rule-2", "/dir/file2", None),
        UnusedExemption("rule-2", "/dir/file3", None),
      )
    }
    "correctly distinguish between exemptions for same rule and file with different text" in {
      val exemptions = Seq(
        RuleExemption("rule-1", Seq("/dir/file1"), Some("some text")),
        RuleExemption("rule-1", Seq("/dir/file1"), Some("other text"))
      )

      val matchedResults = Seq(
        aMatchedResult
          .copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "with some text that matches", isExcluded = true)
      )

      val results = exemptionChecker.run(matchedResults, exemptions)

      results shouldBe Seq(UnusedExemption("rule-1", "/dir/file1", Some("other text")))
    }
    "correctly handle filepaths with and without leading '/'" in {
      val exemptions = Seq(
        RuleExemption("rule-1", Seq("/dir/file1"), Some("some text")),
        RuleExemption("rule-2", Seq("/dir/file2", "dir/file3"), None)
      )

      val matchedResults = Seq(
        aMatchedResult.copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "with some text that matches", isExcluded = true),
        aMatchedResult.copy(ruleId = "rule-2", filePath = "/dir/file2", isExcluded = true),
        aMatchedResult.copy(ruleId = "rule-2", filePath = "/dir/file3", isExcluded = true)
      )

      val results = exemptionChecker.run(matchedResults, exemptions)

      results shouldBe empty
    }
  }
}
