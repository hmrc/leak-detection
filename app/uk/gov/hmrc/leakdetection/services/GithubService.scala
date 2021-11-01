/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.services

import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.leakdetection.config.ConfigLoader

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}


class GithubService @Inject()(httpClient: HttpClient, configLoader: ConfigLoader) {

  import configLoader.cfg

  def getDefaultBranchName(repository: String)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[String] = {
    val githubAccessToken = cfg.githubSecrets.personalAccessToken
    val url = s"${cfg.github.apiUrl}/$repository"
    httpClient.GET[Option[RepoInfo]](
      url = url,
      headers = Seq(("Authorization", s"token $githubAccessToken"))) recover {
      case _ => None
    } map {
      case Some(value) => value.defaultBranch
      case None => "main"
    }
  }
}

final case class RepoInfo(defaultBranch: String = "main")

object RepoInfo {
  implicit val reads: Reads[RepoInfo] =
    (__ \ "default_branch").readWithDefault[String]("main").map(RepoInfo(_))
}