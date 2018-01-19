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

package uk.gov.hmrc.leakdetection.views.helpers

import play.twirl.api.{Html, HtmlFormat}
import uk.gov.hmrc.leakdetection.model.ReportLine

object highlightProblems extends (ReportLine => Html) {

  def apply(r: ReportLine): Html = {
    val spanOpening = "<span class='highlighted'>"
    val spanEnd     = "</span>"
    val overhead    = spanOpening.length + spanEnd.length

    val (highlightedErrors, _) = r.matches.foldLeft((HtmlFormat.escape(r.lineText).toString, 0)) {
      case ((acc, n), m) =>
        val beforeMatch      = acc.substring(0, m.start + n * overhead)
        val highlightedMatch = spanOpening + m.value + spanEnd
        val restOfLine       = acc.substring(m.end + n * overhead)

        (beforeMatch + highlightedMatch + restOfLine, n + 1)
    }
    HtmlFormat.raw(highlightedErrors)
  }

}
