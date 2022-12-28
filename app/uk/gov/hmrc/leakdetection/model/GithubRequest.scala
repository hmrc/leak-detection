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

object GithubRequest {
  val githubReads: Reads[GithubRequest] =
    PushUpdate.githubReads
      .orElse(PushDelete.githubReads)
      .orElse(RepositoryEvent.githubReads)
}

// https://docs.github.com/developers/webhooks-and-events/webhooks/webhook-events-and-payloads#push
// Set to fail when deleted is false
// runMode is for the admin endpoint - TODO change to query param
final case class PushUpdate(
  repositoryName: String,
  isPrivate     : Boolean,
  isArchived    : Boolean,
  authorName    : String,
  branchRef     : String,
  repositoryUrl : String,
  commitId      : String,
  archiveUrl    : String,
  runMode       : Option[RunMode]
) extends GithubRequest

object PushUpdate {
  implicit val rmr = RunMode.format
  val githubReads: Reads[GithubRequest] =
    (__ \ "deleted")
      .read[Boolean]
      .filter(JsonValidationError("delete should be false"))(_ == false)
      .flatMap(_ =>
        ( (__ \ "repository" \ "name"       ).read[String]
        ~ (__ \ "repository" \ "private"    ).read[Boolean]
        ~ (__ \ "repository" \ "archived"   ).read[Boolean]
        ~ (__ \ "pusher"     \ "name"       ).read[String]
        ~ (__ \ "ref"                       ).read[String].map(_.stripPrefix("refs/heads/"))
        ~ (__ \ "repository" \ "url"        ).read[String]
        ~ (__ \ "after"                     ).read[String]
        ~ (__ \ "repository" \ "archive_url").read[String]
        ~ (__ \ "runMode"                   ).readNullable[RunMode]
        )(PushUpdate.apply _)
      )
}

// Also a Push - deleted is true
final case class PushDelete(
  repositoryName: String,
  authorName    : String,
  branchRef     : String,
  repositoryUrl : String
) extends GithubRequest

object PushDelete {
  val githubReads: Reads[GithubRequest] =
    (__ \ "deleted")
      .read[Boolean]
      .filter(JsonValidationError("delete should be true"))(_ == true)
      .flatMap(_ =>
        ( (__ \ "repository" \ "name").read[String]
        ~ (__ \ "pusher"     \ "name").read[String]
        ~ (__ \ "ref"                ).read[String].map(_.stripPrefix("refs/heads/"))
        ~ (__ \ "repository" \ "url" ).read[String]
        )(PushDelete.apply _)
      )
}

// https://docs.github.com/developers/webhooks-and-events/webhooks/webhook-events-and-payloads#repository
final case class RepositoryEvent(
  repositoryName: String,
  action        : String
) extends GithubRequest

object RepositoryEvent {
  val githubReads: Reads[GithubRequest] =
    ( (__ \ "repository" \ "name").read[String]
    ~ (__ \ "action"             ).read[String]
    )(RepositoryEvent.apply _)
}
