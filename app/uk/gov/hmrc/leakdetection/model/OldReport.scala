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

import org.joda.time.DateTime
import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

final case class OldLeakResolution(
  timestamp: DateTime,
  commitId: String
)

case class OldReport(
  _id: ReportId,
  repoName: String,
  repoUrl: String,
  commitId: String,
  branch: String,
  timestamp: DateTime,
  author: String,
  inspectionResults: Seq[ReportLine],
  leakResolution: Option[OldLeakResolution]
)

object OldReport {
  val mongoFormat: OFormat[OldReport] = {
    implicit val mongoDateFormats     = ReactiveMongoFormats.dateTimeFormats
    implicit val leakResolutionFormat = Json.format[OldLeakResolution]
    Json.format[OldReport]
  }
}
