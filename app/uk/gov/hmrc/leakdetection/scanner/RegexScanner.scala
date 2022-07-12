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

import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption}
import uk.gov.hmrc.leakdetection.scanner.MatchedResult.ensureLengthIsBelowLimit

case class RegexScanner(rule: Rule, lineLengthLimit: Int) {

  private val compiledRegex = rule.regex.r

  private object Extractor {
    def unapply(arg: String): Option[(String, List[Match])] =
      compiledRegex.findAllMatchIn(arg)
        .toList
      match {
        case Nil     => None
        case matches => Some((arg, matches.map(Match.create)))
      }
  }

  def scanFileName(text: String, filePath: String, serviceDefinedExemptions: Seq[RuleExemption]): Option[MatchedResult] =
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
            draft       = rule.draft,
            isExcluded  = isFileExempt(rule.id, filePath, serviceDefinedExemptions)
          )
        )
      case _ => None
    }

  def scanLine(line: String, lineNumber: Int, filePath: String, inlineExemption: Boolean, serviceDefinedExemptions: Seq[RuleExemption]): Option[MatchedResult] =
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
              draft       = rule.draft,
              isExcluded  = isLineExempt(rule.id, filePath, line, matches, inlineExemption, serviceDefinedExemptions, rule.ignoredContent) || isFileExempt(rule.id, filePath, serviceDefinedExemptions)
            ),
            lineLengthLimit
          )
        )
      case _ => None
    }

  private def isLineExempt(ruleId: String, filePath: String, line: String, matches: Seq[Match], inlineExemption: Boolean, serviceDefinedExemptions: Seq[RuleExemption], ruleExemptions: List[String]): Boolean = {
    //all matching results musts be covered by the rule exemptions for the line to be considered exempt by the rules ignored content
    def exemptByRule = if (ruleExemptions.isEmpty) false else matches
      .map(m => line.substring(m.start, m.end))
      .forall(t =>
        ruleExemptions.exists(p =>
          p.r.findAllIn(t).nonEmpty)
      )

    //exemptions defined by the service can match any part of the line, not just the matching results
    def exemptByService = serviceDefinedExemptions
      .filter(_.ruleId == ruleId)
      .filter(_.filePaths.contains(filePath))
      .flatMap(_.text)
      .exists(line.contains(_))

    inlineExemption || exemptByRule || exemptByService
  }

  private def isFileExempt(ruleId: String, filePath: String, serviceDefinedExemptions: Seq[RuleExemption]): Boolean = {
      serviceDefinedExemptions
        .filter(_.ruleId == ruleId)
        .filter(_.filePaths.contains(filePath))
        .exists(_.text == None)
  }

}
