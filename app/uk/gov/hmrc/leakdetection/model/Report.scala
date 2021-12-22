/*
 * Copyright 2021 HM Revenue & Customs
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

import java.util.UUID
import java.time.Instant
import play.api.mvc.PathBindable
import uk.gov.hmrc.leakdetection.scanner.{Match, Result}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.play.binders.SimpleObjectBinder
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import uk.gov.hmrc.leakdetection.controllers.AdminController

final case class ReportId(value: String) extends AnyVal {
  override def toString: String = value
}

object ReportId {
  def random = ReportId(UUID.randomUUID().toString)

  implicit val format: Format[ReportId] = new Format[ReportId] {
    def writes(o: ReportId): JsValue = JsString(o.value)

    def reads(json: JsValue): JsResult[ReportId] = json match {
      case JsString(v) => JsSuccess(ReportId(v))
      case _           => JsError("invalid reportId")
    }
  }

  implicit val binder: PathBindable[ReportId] =
    new SimpleObjectBinder[ReportId](ReportId.apply, _.value)
}

final case class ResolvedLeak(ruleId: String, description: String)

object ResolvedLeak {
  implicit val format: OFormat[ResolvedLeak] = Json.format[ResolvedLeak]
}

final case class LeakResolution(
  timestamp    : Instant,
  commitId     : String,
  resolvedLeaks: Seq[ResolvedLeak]
)

object LeakResolution {
  def create(reportWithLeaks: Report, cleanReport: Report): LeakResolution = {
    val resolvedLeaks =
      reportWithLeaks.inspectionResults
        .map(reportLine =>
          ResolvedLeak(
            ruleId      = reportLine.ruleId.getOrElse(""),
            description = reportLine.description
          )
        )
    LeakResolution(
      timestamp     = cleanReport.timestamp,
      commitId      = cleanReport.commitId,
      resolvedLeaks = resolvedLeaks
    )
  }
}

final case class Report(
  id               : ReportId,
  repoName         : String,
  repoUrl          : String,
  commitId         : String,
  branch           : String,
  timestamp        : Instant,
  author           : String,
  inspectionResults: Seq[ReportLine],
  leakResolution   : Option[LeakResolution]
)

object Report {

  def create(
    repositoryName: String,
    repositoryUrl : String,
    commitId      : String,
    authorName    : String,
    branch        : String,
    results       : Seq[Result],
    leakResolution: Option[LeakResolution] = None
  ): Report =
    Report(
      id        = ReportId.random,
      repoName  = repositoryName,
      repoUrl   = repositoryUrl,
      commitId  = commitId,
      branch    = branch,
      timestamp = Instant.now,
      author    = authorName,
      inspectionResults = results.map { r =>
        val commitOrBranch =
          if (commitId == AdminController.NOT_APPLICABLE) {
            branch
          } else {
            commitId
          }
        ReportLine.build(repositoryUrl, commitOrBranch, r)
      },
      leakResolution = leakResolution
    )

  val apiFormat: Format[Report] = {
    // default Instant Reads is fine, but we want Writes to include .SSS even when 000
    val instantFormatter =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    implicit val instantWrites: Writes[Instant] =
      (instant: Instant) => JsString(instantFormatter.format(instant))

    reportFormat
  }

  val mongoFormat: OFormat[Report] =
    reportFormat(MongoJavatimeFormats.instantFormat)

  private def reportFormat(implicit instantFormat: Format[Instant]): OFormat[Report] = {
    implicit val leakResolutionFormat: Format[LeakResolution] =
      ( (__ \ "timestamp"    ).format[Instant]
      ~ (__ \ "commitId"     ).format[String]
      ~ (__ \ "resolvedLeaks").format[Seq[ResolvedLeak]]
      )(LeakResolution.apply, unlift(LeakResolution.unapply))

    ( (__ \ "_id"              ).format[ReportId]
    ~ (__ \ "repoName"         ).format[String]
    ~ (__ \ "repoUrl"          ).format[String]
    ~ (__ \ "commitId"         ).format[String]
    ~ (__ \ "branch"           ).format[String]
    ~ (__ \ "timestamp"        ).format[Instant]
    ~ (__ \ "author"           ).format[String]
    ~ (__ \ "inspectionResults").format[Seq[ReportLine]]
    ~ (__ \ "leakResolution"   ).formatNullable[LeakResolution]
    )(Report.apply, unlift(Report.unapply))
  }
}

final case class ReportLine(
  filePath   : String,
  scope      : String,
  lineNumber : Int,
  urlToSource: String,
  ruleId     : Option[String],
  description: String,
  lineText   : String,
  matches    : List[Match],
  priority   : Option[String],
  isTruncated: Option[Boolean] // todo(konrad) Option due to backwards compatibility, remove after collection cleared
)

object ReportLine {
  def build(repositoryUrl: String, commitIdOrBranch: String, result: Result): ReportLine = {
    val repoUrl: String = repositoryUrl
    new ReportLine(
      filePath    = result.filePath,
      scope       = result.scanResults.scope,
      lineNumber  = result.scanResults.lineNumber,
      urlToSource = s"$repoUrl/blame/$commitIdOrBranch${result.filePath}#L${result.scanResults.lineNumber}",
      ruleId      = Some(result.scanResults.ruleId),
      description = result.scanResults.description,
      lineText    = result.scanResults.lineText,
      matches     = result.scanResults.matches,
      priority    = Some(result.scanResults.priority),
      isTruncated = Some(result.scanResults.isTruncated)
    )
  }

  implicit val format: Format[ReportLine] = Json.format[ReportLine]
}
