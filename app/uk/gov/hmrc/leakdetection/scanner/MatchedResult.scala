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

package uk.gov.hmrc.leakdetection.scanner

import play.api.libs.json.{Format, Json}
import scala.util.matching.Regex

final case class MatchedResult(
  scope: String,
  lineText: String,
  lineNumber: Int,
  ruleId: String,
  description: String,
  matches: List[Match],
  isTruncated: Boolean = false
)

final case class Match(
  start: Int,
  end: Int,
  value: String
)

object Match {
  def create(regexMatch: Regex.Match): Match =
    Match(
      start = regexMatch.start,
      end   = regexMatch.end,
      value = regexMatch.matched
    )
  implicit val format: Format[Match] = Json.format[Match]
}

object MatchedResult {
  implicit val format: Format[MatchedResult] = Json.format[MatchedResult]

  def truncate(matchedResult: MatchedResult, limit: Int): MatchedResult =
    if (matchedResult.lineText.length > limit && matchedResult.matches.nonEmpty) {

      val (_, matchesUpToLimit) =
        matchedResult.matches.foldLeft((0, List.empty[Match])) {
          case ((total, matches), m @ Match(start, end, _)) =>
            if (total + end - start <= limit) {
              (total + end - start, matches :+ m)
            } else {
              (total, matches)
            }
        }

      val joinedConsecutiveMatches =
        matchesUpToLimit
          .foldLeft(List.empty[Match]) {
            case (lastAddedElement :: others, m) =>
              if (lastAddedElement.end == m.start) {
                lastAddedElement.copy(end = lastAddedElement.end + (m.end - m.start)) :: others
              } else {
                m :: lastAddedElement :: others
              }
            case (Nil, m) =>
              List(m)
          }
          .reverse

      val values = joinedConsecutiveMatches.map { m =>
        matchedResult.lineText.substring(m.start, m.end)
      }

      val (_, matchesWithReadjustedIndexes) =
        joinedConsecutiveMatches.zip(values).zipWithIndex.foldLeft(0, List.empty[Match]) {
          case ((totalLength, acc), ((_, value), index)) =>
            if (index == 0) {
              val startPos = totalLength + "[…] ".length
              val endPos   = totalLength + "[…] ".length + value.length
              (endPos, acc :+ Match(startPos, endPos, ""))
            } else {
              val startPos = totalLength + " […] ".length
              val endPos   = totalLength + " […] ".length + value.length
              (endPos, acc :+ Match(startPos, endPos, ""))
            }
        }

      val lineTextWithElipses =
        if (values.nonEmpty) {
          values.mkString("[…] ", " […] ", " […]")
        } else {
          ""
        }

      matchedResult.copy(
        lineText    = lineTextWithElipses,
        matches     = matchesWithReadjustedIndexes,
        isTruncated = true
      )

    } else {
      matchedResult
    }

}
