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

final case class Author(
  name: String,
  email: String,
  username: Option[String]
)

object Author {
  implicit val reads: OFormat[Author] = Json.format[Author]
}

final case class PayloadDetails(
  repositoryName: String,
  isPrivate: Boolean,
  authors: Seq[Author],
  branchRef: String,
  repositoryUrl: String,
  commitId: String,
  archiveUrl: String
)

object PayloadDetails {

  implicit private def str(js: JsLookupResult): String =
    js.as[String]

  private case class AuthorWrapper(author: Author)
  private implicit val format: Format[AuthorWrapper] = Json.format[AuthorWrapper]

  implicit val reads: Reads[PayloadDetails] = (
    (__ \ "repository" \ "name").read[String] and
      (__ \ "repository" \ "private").read[Boolean] and
      (__ \ "commits").read[List[AuthorWrapper]].map(_.map(_.author)) and
      (__ \ "ref").read[String] and
      (__ \ "repository" \ "url").read[String] and
      (__ \ "after").read[String] and
      (__ \ "repository" \ "archive_url").read[String]
  )(PayloadDetails.apply _)

}
