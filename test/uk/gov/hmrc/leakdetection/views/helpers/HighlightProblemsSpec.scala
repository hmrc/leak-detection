/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.views.helpers

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.model.ReportLine
import uk.gov.hmrc.leakdetection.scanner.Match

class HighlightProblemsSpec extends AnyWordSpec with Matchers {

  "Highlighting problems" should {
    "wrap single occurrence of a detected problem in a span html tag" in {
      val rl  = createReportLine("abc foo bar", Match(start = 4, end = 7))
      val res = highlightProblems(rl).body

      val expected = "abc <span class='highlighted'>foo</span> bar"

      res shouldBe expected
    }
    "wrap multiple occurrences of a detected problems in span html tags" in {
      val line = "abc foo bar; baz null; xyz"
      val rl   = createReportLine(line, Match(4, 7), Match(17, 21))
      val res  = highlightProblems(rl).body

      val expected = "abc <span class='highlighted'>foo</span> bar; baz " +
        "<span class='highlighted'>null</span>; xyz"

      res shouldBe expected
    }

    "escapes html characters" in {
      val line = "abc <h1>foo</h1> bar; baz null; xyz"
      val rl   = createReportLine(line, Match(8, 11), Match(26, 30))
      val res  = highlightProblems(rl).body

      val expected = "abc &lt;h1&gt;<span class='highlighted'>foo</span>&lt;/h1&gt; bar; baz " +
        "<span class='highlighted'>null</span>; xyz"

      res shouldBe expected
    }
  }

  def createReportLine(lineText: String, matches: Match*) =
    ReportLine(
      filePath    = null,
      scope       = null,
      lineNumber  = 0,
      urlToSource = null,
      ruleId      = None,
      description = null,
      lineText    = lineText,
      matches     = matches.toList,
      isTruncated = Some(false))

}
