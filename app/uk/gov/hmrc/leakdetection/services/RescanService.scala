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

package uk.gov.hmrc.leakdetection.services

import cats.implicits.*
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.controllers.AdminController.NOT_APPLICABLE
import uk.gov.hmrc.leakdetection.model.*
import uk.gov.hmrc.leakdetection.persistence.RescanRequestsQueueRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RescanService @Inject()(
  teamsAndRepos        : TeamsAndRepositoriesConnector,
  rescanQueue          : RescanRequestsQueueRepository,
  scanningService      : ScanningService,
  activeBranchesService: ActiveBranchesService
)(using ExecutionContext) extends Logging:

  def rescan(repository: Repository, branch: Branch, runMode: RunMode, ruleId: Option[String] = None)(using HeaderCarrier): Future[Option[Future[Report]]] =
    teamsAndRepos.repo(repository.asString)
      .map: f =>
        f.map(repoToPayload(_, runMode))
          .map: p =>
            scanningService
              .scanRepository(
                repository    = repository,
                branch        = branch,
                isPrivate     = p.isPrivate,
                isArchived    = p.isArchived,
                repositoryUrl = p.repositoryUrl,
                commitId      = p.commitId,
                authorName    = p.authorName,
                archiveUrl    = p.archiveUrl,
                runMode       = runMode,
                ruleId        = ruleId
              )

  def rescanAllRepos(runMode: RunMode, ruleId: Option[String] = None): Future[Unit] =
    for
      repos    <- teamsAndRepos.repos()
      payloads =  repos.map(r => repoToPayload(r, runMode, ruleId))
      inserts  <- rescanQueue.pushNewBatch(payloads).map(_.length)
      _        =  logger.info(s"Re-triggered $inserts rescans")
    yield ()

  def triggerRescan(repos: List[String], runMode: RunMode, ruleId: Option[String] = None): Future[Unit] =
    for
      payloads <- repos.foldLeftM(Seq.empty[PushUpdate])((acc, repoName) =>
        teamsAndRepos.repo(repoName).map(r => acc ++ r.map(r => repoToPayload(r, runMode, ruleId)).toSeq)
                  )
      inserts  <- rescanQueue.pushNewBatch(payloads).map(_.length)
      _         = logger.info(s"Re-triggered $inserts rescans")
    yield ()

  def rescanArchivedBranches(repository: Repository, runMode: RunMode, ruleId: Option[String] = None): Future[Unit] =
    for
      branches    <- activeBranchesService.getActiveBranches(Some(repository.asString)).map(_.map(_.branch))
      repoDetails <- teamsAndRepos.repo(repository.asString)
      pushUpdate  =  repoDetails.map(r => repoToPayload(r, runMode, ruleId))
      payloads    =  branches.map(b => pushUpdate.map(p => p.copy(branchRef = b, isArchived = true))).flatten //we need to ensure it's marked as archived as it's unlikely teamsAndRepos knows this yet
      inserts     <- rescanQueue.pushNewBatch(payloads).map(_.length)
      _           =  logger.info(s"Re-triggered $inserts rescans")
    yield ()

  def clearBranch(repositoryName: String, branchRef: String): Future[Unit] =
    rescanQueue.delete(repositoryName, branchRef)

  private def repoToPayload(repoInfo: TeamsAndRepositoriesConnector.RepositoryInfo, runMode: RunMode, ruleId: Option[String] = None): PushUpdate =
    PushUpdate(
      repositoryName = repoInfo.name,
      isPrivate      = repoInfo.isPrivate,
      isArchived     = repoInfo.isArchived,
      authorName     = NOT_APPLICABLE,
      branchRef      = repoInfo.defaultBranch,
      repositoryUrl  = s"https://github.com/hmrc/${repoInfo.name}",
      commitId       = NOT_APPLICABLE,
      archiveUrl     = s"https://api.github.com/repos/hmrc/${repoInfo.name}/{archive_format}{/ref}",
      runMode        = Some(runMode),
      ruleId         = ruleId
    )
