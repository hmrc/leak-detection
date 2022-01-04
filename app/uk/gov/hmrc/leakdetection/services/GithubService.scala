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

package uk.gov.hmrc.leakdetection.services

import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{Branch, Repository}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}


class GithubService @Inject()(httpClient: HttpClient, configLoader: ConfigLoader) {

  import configLoader.cfg

  def getDefaultBranchName(repository: Repository)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Branch] = {
    val githubAccessToken = cfg.githubSecrets.personalAccessToken
    val url = s"${cfg.github.apiUrl}/${repository.asString}"
    httpClient.GET[Option[Branch]](
      url = url,
      headers = Seq(("Authorization", s"token $githubAccessToken"))) map {
      _.getOrElse(Branch.main)
    }
  }
}