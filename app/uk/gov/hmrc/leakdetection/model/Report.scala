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

package uk.gov.hmrc.leakdetection.model

import java.time.Instant
import java.util.UUID
import play.api.libs.json._
import play.api.mvc.PathBindable
import uk.gov.hmrc.leakdetection.scanner.Result
import uk.gov.hmrc.play.binders.SimpleObjectBinder

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
  timestamp: Instant,
  author: String,
  inspectionResults: Seq[ReportLine]
)

object Report {
  def create(payloadDetails: PayloadDetails, results: Seq[Result]) = Report(
    ReportId.random,
    payloadDetails.repositoryName,
    payloadDetails.repositoryUrl,
    payloadDetails.commitId,
    Instant.now(),
    payloadDetails.authorName,
    results.map(r => ReportLine.build(payloadDetails, r))
  )

  implicit val format: Format[Report] = Json.format[Report]

  val mongoFormat: OFormat[Report] = {
    implicit val mongoInstantReads: Reads[Instant] =
      (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

    implicit val mongoInstantWrites: Writes[Instant] = new Writes[Instant] {
      def writes(o: Instant): JsValue = Json.obj("$date" -> o.toEpochMilli)
    }

    Json.format[Report]
  }
}

final case class ReportLine(
  filePath: String,
  lineNumber: Int,
  urlToSource: String,
  description: String,
  lineText: String,
  matches: List[String]
)

object ReportLine {
  def build(payloadDetails: PayloadDetails, result: Result): ReportLine = {
    val repoUrl: String = payloadDetails.repositoryUrl
    val branch          = payloadDetails.branchRef.diff("refs/heads/")
    new ReportLine(
      result.filePath,
      result.scanResults.lineNumber,
      s"$repoUrl/blob/$branch${result.filePath}#L${result.scanResults.lineNumber}",
      result.scanResults.description,
      result.scanResults.lineText,
      result.scanResults.matches
    )
  }

  implicit val format: Format[ReportLine] = Json.format[ReportLine]
}
