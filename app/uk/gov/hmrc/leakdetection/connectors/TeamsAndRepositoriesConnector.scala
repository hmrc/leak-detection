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

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class Team(
  name                    : String,
  firstActiveDate         : Option[LocalDateTime],
  lastActiveDate          : Option[LocalDateTime],
  firstServiceCreationDate: Option[LocalDateTime],
  repos                   : Option[Map[String, Seq[String]]]
) {
  def normalisedName = name.toLowerCase.replaceAll(" ", "_")
}

object Team {
  implicit val format: OFormat[Team] = Json.format[Team]
}

case class RepositoryInfo(
  name         : String,
  isPrivate    : Boolean,
  isArchived   : Boolean,
  defaultBranch: String
)

object RepositoryInfo {
  implicit val format: OFormat[RepositoryInfo] = Json.format[RepositoryInfo]
}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(implicit val ec: ExecutionContext) {
  import HttpReads.Implicits._

  lazy private val baseUrl = servicesConfig.baseUrl("teams-and-repositories")
  implicit private val hc: HeaderCarrier = HeaderCarrier()

  def teamsWithRepositories(): Future[Seq[Team]] =
    httpClientV2
      .get(url"${baseUrl}/api/teams_with_repositories")
      .execute[Seq[Team]]

  def team(teamName: String): Future[Option[Team]] =
    httpClientV2
      .get(url"${baseUrl}/api/teams/${teamName}?includeRepos=true")
      .execute[Option[Team]]

  def repos(): Future[Seq[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories")
      .execute[Seq[RepositoryInfo]]

  def repo(repoName: String): Future[Option[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories/$repoName")
      .execute[Option[RepositoryInfo]]

  def reposWithTeams(teamName: String): Future[Seq[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories?team=$teamName")
      .execute[Seq[RepositoryInfo]]

  def archivedRepos(): Future[Seq[RepositoryInfo]] =
    httpClientV2
      .get(url"${baseUrl}/api/v2/repositories?archived=true")
      .execute[Seq[RepositoryInfo]]
}
