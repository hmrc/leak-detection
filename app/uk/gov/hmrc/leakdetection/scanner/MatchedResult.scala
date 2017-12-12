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

import play.api.libs.json.{Format, Json}
import scala.util.matching.Regex

final case class MatchedResult(
  lineText: String,
  lineNumber: Int,
  ruleId: String,
  description: String,
  matches: List[Match]
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
}
