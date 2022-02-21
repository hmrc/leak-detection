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

import uk.gov.hmrc.leakdetection.connectors.{RepositoryInfo, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.controllers.AdminController.NOT_APPLICABLE
import uk.gov.hmrc.leakdetection.model.PayloadDetails

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import cats.implicits._
import play.api.Logger
import uk.gov.hmrc.leakdetection.persistence.RescanRequestsQueueRepository

@Singleton
class RescanService @Inject()(teamsAndRepos: TeamsAndRepositoriesConnector, rescanQueue: RescanRequestsQueueRepository)(implicit ec: ExecutionContext){

  private val logger = Logger(getClass)

  def triggerRescan(repos: List[String]): Future[Unit] = {
    for {
      payloads <- repos.foldLeftM(Seq.empty[PayloadDetails])((acc, repoName) => teamsAndRepos.repo(repoName).map(r => acc ++ r.map(repoToPayload).toSeq))
      inserts  <- rescanQueue.pushNewBatch(payloads).map(_.length)
      _         = logger.info(s"Re-triggered $inserts rescans")
    } yield ()
  }

  private def repoToPayload(repoInfo: RepositoryInfo): PayloadDetails = {
    PayloadDetails(
      repositoryName = repoInfo.name,
      isPrivate      = repoInfo.isPrivate,
      authorName     = NOT_APPLICABLE,
      branchRef      = repoInfo.defaultBranch,
      repositoryUrl  = s"https://github.com/hmrc/${repoInfo.name}",
      commitId       = NOT_APPLICABLE,
      archiveUrl     = s"https://api.github.com/repos/hmrc/${repoInfo.name}/{archive_format}{/ref}",
      deleted        = false
    )
  }

}
