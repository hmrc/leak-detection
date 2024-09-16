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

import play.api.libs.functional.syntax.*
import play.api.libs.json.{Format, JsResult, OFormat, __}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant

enum WarningMessageType:
  case MissingRepositoryYamlFile
  case InvalidEntry
  case MissingEntry
  case ParseFailure
  case FileLevelExemptions
  case UnusedExemptions

case class Warning(
  repoName          : String,
  branch            : String,
  timestamp         : Instant,
  reportId          : ReportId,
  warningMessageType: String
)

object Warning:
  val apiFormat: OFormat[Warning] =
    ( (__ \ "repoName" ).format[String]
    ~ (__ \ "branch"   ).format[String]
    ~ (__ \ "timestamp").format[Instant]
    ~ (__ \ "reportId" ).format[ReportId]
    ~ (__ \ "message"  ).format[String]
    )(Warning.apply, o => Tuple.fromProductTyped(o))

  def mongoFormat: OFormat[Warning] =
    ( (__ \ "repoName"          ).format[String]
    ~ (__ \ "branch"            ).format[String]
    ~ (__ \ "timestamp"         ).format[Instant](MongoJavatimeFormats.instantFormat)
    ~ (__ \ "reportId"          ).format[ReportId]
    ~ (__ \ "warningMessageType").format[String]
    )(Warning.apply, o => Tuple.fromProductTyped(o))
