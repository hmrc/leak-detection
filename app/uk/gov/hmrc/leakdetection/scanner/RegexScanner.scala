/*
 * Copyright 2022 HM Revenue & Customs
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
import MatchedResult.ensureLengthIsBelowLimit

case class RegexScanner(rule: Rule, lineLengthLimit: Int, lineExemptions: Seq[String] = Seq()) {

  private val compiledRegex = rule.regex.r

  private object Extractor {
    def unapply(arg: String): Option[(String, List[Match])] =
      compiledRegex.findAllMatchIn(arg)
        .toList
        .filterNot(_ => lineExemptions.exists(exemption => arg.contains(exemption)))
      match {
        case Nil     => None
        case matches => Some((arg, matches.map(Match.create)))
      }
  }

  def scanFileName(text: String, filePath: String): Option[MatchedResult] =
    text match {
      case Extractor(_, matches) =>
        Some(
          MatchedResult(
            filePath    = filePath,
            scope       = Rule.Scope.FILE_NAME,
            lineText    = text,
            lineNumber  = 1,
            ruleId      = rule.id,
            description = rule.description,
            matches     = matches,
            priority    = rule.priority,
            draft       = rule.draft
          )
        )
      case _ => None
    }

  def scanLine(line: String, lineNumber: Int, filePath: String): Option[MatchedResult] =
    line match {
      case Extractor(lineText, matches) =>
        Some(
          ensureLengthIsBelowLimit(
            MatchedResult(
              filePath    = filePath,
              scope       = Rule.Scope.FILE_CONTENT,
              lineText    = lineText,
              lineNumber  = lineNumber,
              ruleId      = rule.id,
              description = rule.description,
              matches     = matches,
              priority    = rule.priority,
              draft       = rule.draft
            ),
            lineLengthLimit
          )
        )
      case _ => None
    }

}
