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

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import scala.language.implicitConversions

final case class PayloadDetails(
  repositoryName: String,
  isPrivate: Boolean,
  authorName: String,
  branchRef: String,
  repositoryUrl: String,
  commitId: String,
  archiveUrl: String,
  deleted: Boolean
)

object PayloadDetails {

  implicit val reads: Reads[PayloadDetails] = (
    (__ \ "repository" \ "name").read[String] and
      (__ \ "repository" \ "private").read[Boolean] and
      (__ \ "pusher" \ "name").read[String] and
      (__ \ "ref").read[String] and
      (__ \ "repository" \ "url").read[String] and
      (__ \ "after").read[String] and
      (__ \ "repository" \ "archive_url").read[String] and
      (__ \ "deleted").read[Boolean]
  )(PayloadDetails.apply _)

}
