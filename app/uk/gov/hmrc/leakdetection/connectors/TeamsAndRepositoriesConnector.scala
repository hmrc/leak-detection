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

package uk.gov.hmrc.leakdetection.connectors

import play.api.libs.json._
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


object TeamsAndRepositoriesConnector:
  import play.api.libs.functional.syntax._

  case class TeamSummary(
    name          : String,
    lastActiveDate: Option[Instant],
    repos         : Seq[String]
  ):
    val normalisedName = name.toLowerCase.replaceAll(" ", "_")

  object TeamSummary:
    val apiReads: Reads[TeamSummary] =
      ( (__ \ "name"          ).read[String]
      ~ (__ \ "lastActiveDate").readNullable[Instant]
      ~ (__ \ "repos"         ).read[Seq[String]]
      )(TeamSummary.apply _)

  case class RepositoryInfo(
    name         : String,
    isPrivate    : Boolean,
    isArchived   : Boolean,
    defaultBranch: String
  )

  object RepositoryInfo:
    val apiReads: Reads[RepositoryInfo] =
      ( (__ \ "name"         ).read[String]
      ~ (__ \ "isPrivate"    ).read[Boolean]
      ~ (__ \ "isArchived"   ).read[Boolean]
      ~ (__ \ "defaultBranch").read[String]
      )(RepositoryInfo.apply _)

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(using ExecutionContext):
  import HttpReads.Implicits._
  import TeamsAndRepositoriesConnector._

  lazy private val baseUrl = servicesConfig.baseUrl("teams-and-repositories")
  given HeaderCarrier = HeaderCarrier()

  private given Reads[TeamSummary]    = TeamSummary.apiReads
  private given Reads[RepositoryInfo] = RepositoryInfo.apiReads

  def teams(): Future[Seq[TeamSummary]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/teams")
      .execute[Seq[TeamSummary]]

  def repos(
    teamName      : Option[String] = None
  , digitalService: Option[String] = None
  ): Future[Seq[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories?team=$teamName&digitalServiceName=$digitalService")
      .execute[Seq[RepositoryInfo]]

  def repo(repoName: String): Future[Option[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories/$repoName")
      .execute[Option[RepositoryInfo]]

  def archivedRepos(): Future[Seq[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories?archived=true")
      .execute[Seq[RepositoryInfo]]
