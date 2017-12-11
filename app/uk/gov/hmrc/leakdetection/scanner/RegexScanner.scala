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

import uk.gov.hmrc.leakdetection.config.Rule

class RegexScanner(rule: Rule) {

  private val compiledRegex = rule.regex.r

  private object Extractor {
    def unapply(arg: String): Option[(String, List[String])] =
      compiledRegex.findAllMatchIn(arg).toList match {
        case Nil     => None
        case matches => Some((arg, matches.map(_.matched)))
      }
  }

  def scan(text: String): Seq[MatchedResult] =
    text.lines.toSeq.zipWithIndex
      .collect {
        case (Extractor(lineText, matches), lineNumber) =>
          MatchedResult(
            lineText    = lineText,
            lineNumber  = adjustForBase1Numbering(lineNumber),
            ruleId      = rule.id,
            description = rule.description,
            matches     = matches
          )
      }

  def adjustForBase1Numbering(i: Int): Int = i + 1

}
