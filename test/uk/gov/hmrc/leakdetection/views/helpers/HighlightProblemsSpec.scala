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

package uk.gov.hmrc.leakdetection.views.helpers

import org.scalatest.{Matchers, WordSpec}
import uk.gov.hmrc.leakdetection.model.ReportLine
import uk.gov.hmrc.leakdetection.scanner.Match

class HighlightProblemsSpec extends WordSpec with Matchers {

  "Highlighting problems" should {
    "wrap single occurrence of a detected problem in a span html tag" in {
      val rl  = createReportLine("abc foo bar", Match(4, 7, "foo"))
      val res = highlightProblems(rl).body

      val expected = "abc <span class='highlighted'>foo</span> bar"

      res shouldBe expected
    }
    "wrap multiple occurrences of a detected problems in span html tags" in {
      val line = "abc foo bar; baz null; xyz"
      val rl   = createReportLine(line, Match(4, 7, "foo"), Match(17, 21, "null"))
      val res  = highlightProblems(rl).body

      val expected = "abc <span class='highlighted'>foo</span> bar; baz " +
        "<span class='highlighted'>null</span>; xyz"

      res shouldBe expected
    }
  }

  def createReportLine(lineText: String, matches: Match*) =
    ReportLine(null, 0, null, null, lineText, matches.toList)

}
