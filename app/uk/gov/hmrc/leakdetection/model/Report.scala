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

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.leakdetection.scanner.Result

case class Report(
  repoName: String,
  repoUrl: String,
  commitId: String,
  authors: Seq[Author],
  inspectionResults: Seq[ReportLine]) {}

object Report {
  def create(payloadDetails: PayloadDetails, results: Seq[Result]) = Report(
    payloadDetails.repositoryName,
    payloadDetails.repositoryUrl,
    payloadDetails.commitId,
    payloadDetails.authors,
    results.map(r => ReportLine.build(payloadDetails, r))
  )

  implicit val format: Format[Report] = Json.format[Report]
}

case class ReportLine(filePath: String, lineNumber: Int, urlToSource: String, description: String)

object ReportLine {
  def build(payloadDetails: PayloadDetails, result: Result): ReportLine = {
    val repoUrl: String = payloadDetails.repositoryUrl
    val branch          = payloadDetails.branchRef.diff("refs/heads/")
    new ReportLine(
      result.filePath,
      result.scanResults.lineNumber,
      s"$repoUrl/blob/$branch${result.filePath}#L${result.scanResults.lineNumber}",
      result.scanResults.description
    )
  }

  implicit val format: Format[ReportLine] = Json.format[ReportLine]
}
