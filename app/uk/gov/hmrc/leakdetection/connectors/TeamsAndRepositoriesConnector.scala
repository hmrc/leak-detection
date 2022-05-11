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

package uk.gov.hmrc.leakdetection.connectors

import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, StringContextOps}
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
  implicit val format = Json.format[Team]
}

case class RepositoryInfo(
  name: String,
  isPrivate: Boolean,
  isArchived: Boolean,
  defaultBranch: String
)

object RepositoryInfo {
  implicit val format = Json.format[RepositoryInfo]
}

@Singleton class TeamsAndRepositoriesConnector @Inject()(http: HttpClient, servicesConfig: ServicesConfig)(implicit val ec: ExecutionContext) {

  lazy private val baseUrl = servicesConfig.baseUrl("teams-and-repositories")

  def teamsWithRepositories(): Future[Seq[Team]] = {
    implicit val hc = HeaderCarrier()
    http.GET[Seq[Team]](url"${baseUrl}/api/teams_with_repositories")
  }

  def team(teamName: String): Future[Option[Team]] = {
    implicit val hc = HeaderCarrier()
    http.GET[Option[Team]](url"${baseUrl}/api/teams/${teamName}?includeRepos=true")
  }

  def repos(): Future[Seq[RepositoryInfo]] = {
      implicit val hc = HeaderCarrier()
      http.GET[Seq[RepositoryInfo]](url"${baseUrl}/api/v2/repositories")
  }

  def repo(repoName: String): Future[Option[RepositoryInfo]] = {
      implicit val hc = HeaderCarrier()
      http.GET[Option[RepositoryInfo]](url"${baseUrl}/api/v2/repositories/$repoName")
  }

  def reposWithTeams(teamName: String): Future[Option[Seq[RepositoryInfo]]] = {
      implicit val hc = HeaderCarrier()
      http.GET[Option[Seq[RepositoryInfo]]](url"${baseUrl}/api/v2/repositories?team=$teamName")
  }

  def archivedRepos(): Future[Seq[RepositoryInfo]] = {
      implicit val hc = HeaderCarrier()
      http.GET[Seq[RepositoryInfo]](url"${baseUrl}/api/v2/repositories?archived=true")
  }
}
