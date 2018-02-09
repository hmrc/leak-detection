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

package uk.gov.hmrc.leakdetection.model

import java.util.UUID
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.mvc.PathBindable
import uk.gov.hmrc.leakdetection.scanner.{Match, Result}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.play.binders.SimpleObjectBinder
import uk.gov.hmrc.time.DateTimeUtils

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

final case class Report(
  _id: ReportId,
  repoName: String,
  repoUrl: String,
  commitId: String,
  branch: String,
  timestamp: DateTime,
  author: String,
  inspectionResults: Seq[ReportLine]
)

object Report {

  def create(
    repositoryName: String,
    repositoryUrl: String,
    commitId: String,
    authorName: String,
    branch: String,
    results: Seq[Result]): Report =
    Report(
      _id               = ReportId.random,
      repoName          = repositoryName,
      repoUrl           = repositoryUrl,
      commitId          = commitId,
      branch            = branch.replaceFirst("refs/heads/", ""),
      timestamp         = DateTimeUtils.now,
      author            = authorName,
      inspectionResults = results.map(r => ReportLine.build(repositoryUrl, branch, r))
    )

  implicit val format: Format[Report] = {

    implicit val f = uk.gov.hmrc.http.controllers.RestFormats.dateTimeFormats
    Json.format[Report]
  }

  val mongoFormat: OFormat[Report] = {

    implicit val mf = ReactiveMongoFormats.dateTimeFormats
    Json.format[Report]
  }
}

final case class ReportLine(
  filePath: String,
  scope: String,
  lineNumber: Int,
  urlToSource: String,
  description: String,
  lineText: String,
  matches: List[Match]
)

object ReportLine {
  def build(repositoryUrl: String, branchRef: String, result: Result): ReportLine = {
    val repoUrl: String = repositoryUrl
    val branch          = branchRef.replaceFirst("refs/heads/", "")
    new ReportLine(
      result.filePath,
      result.scanResults.scope,
      result.scanResults.lineNumber,
      s"$repoUrl/blame/$branch${result.filePath}#L${result.scanResults.lineNumber}",
      result.scanResults.description,
      result.scanResults.lineText,
      result.scanResults.matches
    )
  }

  implicit val format: Format[ReportLine] = Json.format[ReportLine]
}
