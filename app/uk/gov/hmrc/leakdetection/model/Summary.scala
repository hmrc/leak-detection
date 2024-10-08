/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.model

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.leakdetection.config.Rule

import java.time.Instant

case class Summary(rule: Rule, repositorySummary: Seq[RepositorySummary])

object Summary:
  val apiFormat: OFormat[Summary] =
    given Format[RepositorySummary] = RepositorySummary.format
    ( (__ \ "rule" ).format[Rule]
    ~ (__ \ "leaks").format[Seq[RepositorySummary]]
    )(Summary.apply, s => Tuple.fromProductTyped(s))

case class RepositorySummary(
  repository     : String,
  isArchived     : Boolean,
  firstScannedAt : Instant,
  lastScannedAt  : Instant,
  warningCount   : Int,
  unresolvedCount: Int,
  excludedCount  : Int,
  branchSummary  : Option[Seq[BranchSummary]]
)

object RepositorySummary:
  given OFormat[BranchSummary] = BranchSummary.format
  val format: OFormat[RepositorySummary] =
    Json.format[RepositorySummary]

case class BranchSummary(
  branch         : String,
  reportId       : String,
  scannedAt      : Instant,
  warningCount   : Int,
  unresolvedCount: Int,
  excludedCount  : Int
)

object BranchSummary:
  val format: OFormat[BranchSummary] =
    Json.format[BranchSummary]
