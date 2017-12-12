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

import play.twirl.api.{Html, HtmlFormat}
import uk.gov.hmrc.leakdetection.model.ReportLine

object highlightProblems extends (ReportLine => Html) {

  def apply(r: ReportLine): Html = {
    val highlightedErrors = r.matches.foldLeft(r.lineText) {
      case (acc, m) =>
        r.lineText.substring(0, m.start) +
          "<span class='highlighted'>" + m.value + "</span>" +
          r.lineText.substring(m.end)
    }
    HtmlFormat.raw(s"<p class='monospace'>$highlightedErrors</p>")
  }

}
