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

import play.api.libs.json._
import play.api.libs.functional.syntax._
import uk.gov.hmrc.leakdetection.config.Rule


case class RuleViolations(rule: Rule, violations: Seq[Report])

object RuleViolations {
  val apiFormat: OFormat[RuleViolations] = {
    implicit val rptf = Report.apiFormat
    implicit val rf = Rule.format
    (
      (__ \ "rule").format[Rule]
    ~ (__ \ "violations").format[Seq[Report]]
    )(RuleViolations.apply, unlift(RuleViolations.unapply))
  }
}

case class RuleIdViolations(ruleId: String, violations: Seq[Report])

object RuleIdViolations {
  val mongoFormat: OFormat[RuleIdViolations] = {
    implicit val rptf = Report.mongoFormat
    (
      (__ \ "ruleId").format[String]
    ~ (__ \ "violations").format[Seq[Report]]
    )(RuleIdViolations.apply, unlift(RuleIdViolations.unapply))
  }
}