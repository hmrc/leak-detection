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

import uk.gov.hmrc.leakdetection.scanner.{Match, MatchedResult}

import java.time.Instant
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

case class Leak(
  repoName   : String,
  branch     : String,
  timestamp  : Instant,
  reportId   : ReportId,
  ruleId     : String,
  description: String,
  filePath   : String,
  scope      : String,
  lineNumber : Int,
  urlToSource: String,
  lineText   : String,
  matches    : List[Match],
  priority   : String,
  isExcluded : Boolean
)

object Leak {

  val apiFormat: OFormat[Leak] = {
    implicit val mf = Match.format
    ( (__ \ "repoName").format[String]
    ~ (__ \ "branch").format[String]
    ~ (__ \ "timestamp").format[Instant]
    ~ (__ \ "reportId").format[ReportId](ReportId.format)
    ~ (__ \ "ruleId").format[String]
    ~ (__ \ "description").format[String]
    ~ (__ \ "filePath").format[String]
    ~ (__ \ "scope").format[String]
    ~ (__ \ "lineNumber").format[Int]
    ~ (__ \ "urlToSource").format[String]
    ~ (__ \ "lineText").format[String]
    ~ (__ \ "matches").format[List[Match]]
    ~ (__ \ "priority").format[String]
    ~ (__ \ "isExcluded").format[Boolean]
    )(Leak.apply, unlift(Leak.unapply))
  }

  val mongoFormat: OFormat[Leak] = {
    implicit val mf = Match.format
    ( (__ \ "repoName").format[String]
    ~ (__ \ "branch").format[String]
    ~ (__ \ "timestamp").format[Instant](MongoJavatimeFormats.instantFormat)
    ~ (__ \ "reportId").format[ReportId](ReportId.format)
    ~ (__ \ "ruleId").format[String]
    ~ (__ \ "description").format[String]
    ~ (__ \ "filePath").format[String]
    ~ (__ \ "scope").format[String]
    ~ (__ \ "lineNumber").format[Int]
    ~ (__ \ "urlToSource").format[String]
    ~ (__ \ "lineText").format[String]
    ~ (__ \ "matches").format[List[Match]]
    ~ (__ \ "priority").format[String]
    ~ (__ \ "isExcluded").formatWithDefault[Boolean](false)
    )(Leak.apply, unlift(Leak.unapply))
  }

  def createFromMatchedResults(report: Report, results: List[MatchedResult]): List[Leak] =
    results.map(result =>
      Leak(
        repoName    = report.repoName,
        branch      = report.branch,
        timestamp   = report.timestamp,
        reportId    = report.id,
        ruleId      = result.ruleId,
        description = result.description,
        filePath    = result.filePath,
        scope       = result.scope,
        lineNumber  = result.lineNumber,
        urlToSource = if(report.commitId == "n/a") url"${report.repoUrl}/blob/${report.branch}${result.filePath}#L${result.lineNumber}".toString
                      else url"${report.repoUrl}/blame/${report.commitId}${result.filePath}#L${result.lineNumber}".toString,
        lineText    = result.lineText,
        matches     = result.matches,
        priority    = result.priority,
        isExcluded  = result.isExcluded
      )
    )
}
