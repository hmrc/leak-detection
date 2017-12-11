/*
 * Copyright 2017 HM Revenue & Customs
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

import org.scalatest.{FreeSpec, Matchers}
import uk.gov.hmrc.leakdetection.config.Rule

class RegexScannerSpec extends FreeSpec with Matchers {

  "scan" - {
    "should look for a regex in a given text" - {
      "and find return the line number matching the regex" in {
        val text =
          """nothing matching here
            |this matches the regex
            |this matches the regex too
            |nothing matching here
            |""".stripMargin
        val descr  = "descr for regex"
        val ruleId = "rule-1"
        val rule   = Rule(ruleId, "(matches)", descr)

        new RegexScanner(rule).scan(text) should
          contain theSameElementsAs Seq(
          MatchedResult("this matches the regex", lineNumber = 2, ruleId, descr, List("matches")),
          MatchedResult(
            "this matches the regex too",
            lineNumber = 3,
            ruleId,
            descr,
            List("matches"))
        )
      }

      "and return empty seq if text doesn't have matching lines for the given regex" in {
        val text   = "this is a test"
        val ruleId = "rule-1"

        val rule = Rule(ruleId, "(was)", "descr")

        new RegexScanner(rule).scan(text) shouldBe Nil
      }
    }
  }
}
