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

import play.api.libs.json.{Format, Json}
import scala.util.matching.Regex

final case class MatchedResult(
  filePath: String,
  scope: String,
  lineText: String,
  lineNumber: Int,
  ruleId: String,
  description: String,
  matches: List[Match],
  priority: String,
  draft: Boolean = false,
  isExcluded: Boolean = false
)

final case class Match(
  start: Int,
  end: Int
) {
  def length: Int = end - start
}

object Match {
  def create(regexMatch: Regex.Match): Match =
    Match(
      start = regexMatch.start,
      end   = regexMatch.end
    )
  implicit val format: Format[Match] = Json.format[Match]
}

object MatchedResult {
  implicit val format: Format[MatchedResult] = Json.format[MatchedResult]

  def ensureLengthIsBelowLimit(matchedResult: MatchedResult, limit: Int): MatchedResult =
    if (matchedResult.lineText.length > limit && matchedResult.matches.nonEmpty) {
      truncate(matchedResult, limit)
    } else {
      matchedResult
    }

  private def truncate(matchedResult: MatchedResult, limit: Int): MatchedResult = {

    val matchesUpToLimit: List[Match] = {
      def cumulativeSum(xs: List[Int]): List[Int] =
        xs.scanLeft(0) { case (acc, current) => acc + current }.drop(1)

      val numberOfMatchesUnderLimit =
        cumulativeSum(matchedResult.matches.map(_.length))
          .count(_ <= limit)

      matchedResult.matches.take(numberOfMatchesUnderLimit)
    }

    val joinedConsecutiveMatches =
      matchesUpToLimit
        .foldLeft(List.empty[Match]) {
          case (lastAddedElement :: others, m) =>
            if (lastAddedElement.end == m.start) {
              lastAddedElement.copy(end = m.end) :: others
            } else {
              m :: lastAddedElement :: others
            }
          case (Nil, m) =>
            List(m)
        }
        .reverse

    val (_, matchesWithReadjustedIndexes) =
      joinedConsecutiveMatches.zipWithIndex.foldLeft((0, List.empty[Match])) {
        case ((totalLength, acc), (m, index)) =>
          if (index == 0) {
            val startPos = totalLength + "[…] ".length
            val endPos   = totalLength + "[…] ".length + m.length
            (endPos, acc :+ Match(startPos, endPos))
          } else {
            val startPos = totalLength + " […] ".length
            val endPos   = totalLength + " […] ".length + m.length
            (endPos, acc :+ Match(startPos, endPos))
          }
      }

    val values = joinedConsecutiveMatches.map { m =>
      matchedResult.lineText.substring(m.start, m.end)
    }

    val lineTextWithElipses =
      if (values.nonEmpty) {
        values.mkString("[…] ", " […] ", " […]")
      } else {
        ""
      }

    matchedResult.copy(
      lineText    = lineTextWithElipses,
      matches     = matchesWithReadjustedIndexes
    )
  }

}
