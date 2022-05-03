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

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

sealed trait GithubRequest extends Product with Serializable

final case class PayloadDetails(
  repositoryName: String,
  isPrivate     : Boolean,
  isArchived    : Boolean,
  authorName    : String,
  branchRef     : String,
  repositoryUrl : String,
  commitId      : String,
  archiveUrl    : String,
  deleted       : Boolean,
  runMode       : Option[RunMode]
) extends GithubRequest

object PayloadDetails {
  implicit val rmr = RunMode.format
  val githubReads: Reads[PayloadDetails] =
    ( (__ \ "repository" \ "name"       ).read[String]
    ~ (__ \ "repository" \ "private"    ).read[Boolean]
    ~ (__ \ "repository" \ "archived"   ).read[Boolean]
    ~ (__ \ "pusher"     \ "name"       ).read[String]
    ~ (__ \ "ref"                       ).read[String].map(_.stripPrefix("refs/heads/"))
    ~ (__ \ "repository" \ "url"        ).read[String]
    ~ (__ \ "after"                     ).read[String]
    ~ (__ \ "repository" \ "archive_url").read[String]
    ~ (__ \ "deleted"                   ).read[Boolean]
    ~ (__ \ "runMode"                   ).readNullable[RunMode]
    )(PayloadDetails.apply _)
      .filter(JsonValidationError("Delete event is not valid"))(!_.deleted)
}

final case class DeleteBranchEvent(
  repositoryName: String,
  authorName    : String,
  branchRef     : String,
  deleted       : Boolean,
  repositoryUrl : String
) extends GithubRequest

object DeleteBranchEvent {
  val githubReads: Reads[DeleteBranchEvent] =
    ( (__ \ "repository" \ "name").read[String]
    ~ (__ \ "pusher"     \ "name").read[String]
    ~ (__ \ "ref"                ).read[String].map(_.stripPrefix("refs/heads/"))
    ~ (__ \ "deleted"            ).read[Boolean]
    ~ (__ \ "repository" \ "url" ).read[String]
    )(DeleteBranchEvent.apply _)
      .filter(JsonValidationError("Not a delete event"))(_.deleted)
}

final case class RepositoryEvent(
  repositoryName: String,
  action        : String
) extends GithubRequest

object RepositoryEvent {
  val githubReads: Reads[RepositoryEvent] =
    ( (__ \ "repository" \ "name").read[String]
    ~ (__ \ "action"             ).read[String]
    )(RepositoryEvent.apply _)
}

/**
  * Test message sent by Github when webhook is created.
  * Should be ignored with 200 OK.
  */
final case class ZenMessage(zen: String) extends GithubRequest

object ZenMessage {
  val githubReads: Reads[ZenMessage] =
    implicitly[Reads[String]].map(ZenMessage.apply)
}
