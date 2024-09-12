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
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.mvc.PathBindable
import uk.gov.hmrc.leakdetection.binders.SimpleObjectBinder
import uk.gov.hmrc.leakdetection.scanner.MatchedResult
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant
import java.util.UUID

case class ReportId(value: String) extends AnyVal:
  override def toString: String = value

object ReportId:
  def random: ReportId = ReportId(UUID.randomUUID().toString)

  given Format[ReportId] =
    Format.of[String]
      .inmap(ReportId.apply, _.toString)

  given PathBindable[ReportId] =
    SimpleObjectBinder[ReportId](ReportId.apply, _.value)

case class RuleId(value: String) extends AnyVal:
  override def toString: String = value

object RuleId:
  given Format[RuleId] =
    Format.of[String]
      .inmap(RuleId.apply, _.toString)

case class ResolvedLeak(ruleId: String, description: String)

object ResolvedLeak:
  given OFormat[ResolvedLeak] = Json.format[ResolvedLeak]

case class UnusedExemption(
  ruleId  : String,
  filePath: String,
  text    : Option[String]
)

object UnusedExemption:
  given OFormat[UnusedExemption] =
    ( (__ \ "ruleId").format[String]
    ~ ( __ \ "filePath").formatWithDefault[String]("")
    ~ ( __ \ "text").formatNullable[String]
    )(UnusedExemption.apply, u => Tuple.fromProductTyped(u))

case class Report(
  id               : ReportId,
  repoName         : String,
  repoUrl          : String,
  commitId         : String,
  branch           : String,
  timestamp        : Instant,
  author           : String,
  totalLeaks       : Int,
  totalWarnings    : Int = 0,
  rulesViolated    : Map[RuleId, Int],
  exclusions       : Map[RuleId, Int],
  unusedExemptions : Seq[UnusedExemption]
)

object Report:

  def createFromMatchedResults(
    repositoryName   : String,
    repositoryUrl    : String,
    commitId         : String,
    authorName       : String,
    branch           : String,
    results          : Seq[MatchedResult],
    unusedExemptions : Seq[UnusedExemption],
    timestamp        : Instant = Instant.now()
  ): Report =
    Report(
      id               = ReportId.random,
      repoName         = repositoryName,
      repoUrl          = repositoryUrl,
      commitId         = commitId,
      branch           = branch,
      timestamp        = timestamp,
      author           = authorName,
      totalLeaks       = results.filterNot(_.isExcluded).length,
      rulesViolated    = results.filterNot(_.isExcluded).groupBy(r => RuleId(r.ruleId)).view.mapValues(_.length).toMap,
      exclusions       = results.filter(_.isExcluded).groupBy(r => RuleId(r.ruleId)).view.mapValues(_.length).toMap,
      unusedExemptions = unusedExemptions
    )

  private val ruleIdMapFormat: Format[Map[RuleId, Int]] =
    Format.of[Map[String, Int]].inmap(
      _.map { case (k, v) => (RuleId(k), v) },
      _.map { case (RuleId(k), v) => (k, v) }
    )

  val apiFormat: Format[Report] =
    // default Instant Reads is fine, but we want Writes to include .SSS even when 000
    val instantFormatter =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    given Writes[Instant] =
      (instant: Instant) => JsString(instantFormatter.format(instant))

    reportFormat

  val mongoFormat: OFormat[Report] =
    reportFormat(using MongoJavatimeFormats.instantFormat)

  private def reportFormat(using Format[Instant]): OFormat[Report] = {
    ( (__ \ "_id"              ).format[ReportId]
    ~ (__ \ "repoName"         ).format[String]
    ~ (__ \ "repoUrl"          ).format[String]
    ~ (__ \ "commitId"         ).format[String]
    ~ (__ \ "branch"           ).format[String]
    ~ (__ \ "timestamp"        ).format[Instant]
    ~ (__ \ "author"           ).format[String]
    ~ (__ \ "totalLeaks"       ).format[Int]
    ~ (__ \ "totalWarnings"    ).formatWithDefault[Int](0)
    ~ (__ \ "rulesViolated"    ).format[Map[RuleId, Int]](ruleIdMapFormat)
    ~ (__ \ "exclusions"       ).formatWithDefault[Map[RuleId, Int]](Map.empty)(ruleIdMapFormat)
    ~ (__ \ "unusedExemptions" ).formatWithDefault[Seq[UnusedExemption]](Seq.empty)
    )(Report.apply, r => Tuple.fromProductTyped(r))
  }
