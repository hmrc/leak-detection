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

import uk.gov.hmrc.leakdetection.scanner.Match

import java.time.Instant
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

case class Leak( repoName   : String,
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
                 priority   : String)

object Leak {

  val apiFormat: OFormat[Leak] =  {
    implicit val mf = Match.format
    (
      (__ \ "repoName").format[String]
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
    )(Leak.apply, unlift(Leak.unapply))
  }

  def mongoFormat: OFormat[Leak] = {
    implicit val mf = Match.format
    (
      (__ \ "repoName").format[String]
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
    )(Leak.apply, unlift(Leak.unapply))
  }

}