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

package uk.gov.hmrc.leakdetection.model

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.leakdetection.config.Rule

import java.time.Instant

case class Summary(rules: Seq[Rule], repositorySummary: Seq[RepositorySummary])

object Summary {
  val apiFormat = {
    implicit val rf = Rule.format
    implicit val vsf = RepositorySummary.format
    (
      (__ \ "rules").format[Seq[Rule]]
        ~ (__ \ "repositories").format[Seq[RepositorySummary]]
      ) (Summary.apply, unlift(Summary.unapply))
  }
}

case class RepositorySummary(
                              repository: String,
                              firstScannedAt: Instant,
                              lastScannedAt: Instant,
                              warningCount: Int,
                              unresolvedCount: Int,
                              branchSummary: Seq[BranchSummary]
                            )

object RepositorySummary {
  implicit val rf = BranchSummary.format
  val format = Json.format[RepositorySummary]
}

case class BranchSummary(
                          branch: String,
                          reportId: ReportId,
                          scannedAt: Instant,
                          warningCount: Int,
                          unresolvedCount: Int,
                          rulesViolated: Map[String, Int]
                        )

object BranchSummary {
  val format = Json.format[BranchSummary]
}