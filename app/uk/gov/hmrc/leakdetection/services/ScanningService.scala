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

import java.io.File
import java.time.Instant

import javax.inject.{Inject, Singleton}
import org.apache.commons.io.FileUtils
import play.api.{Configuration, Logger}
import play.api.libs.iteratee.{Enumerator, Iteratee}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{DeleteBranchEvent, PayloadDetails, Report}
import uk.gov.hmrc.leakdetection.persistence.GithubRequestsQueueRepository
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.leakdetection.services.ArtifactService.{BranchNotFound, ExplodedZip}
import uk.gov.hmrc.workitem.{ProcessingStatus, WorkItem}

import scala.util.control.NonFatal

@Singleton
class ScanningService @Inject()(
  configuration: Configuration,
  artifactService: ArtifactService,
  configLoader: ConfigLoader,
  reportsService: ReportsService,
  alertingService: AlertingService,
  githubRequestsQueueRepository: GithubRequestsQueueRepository,
  repoVisibilityChecker: RepoVisiblityChecker)(implicit ec: ExecutionContext) {

  import configLoader.cfg

  val logger = Logger(getClass)

  lazy val privateMatchingEngine = new RegexMatchingEngine(cfg.allRules.privateRules, cfg.maxLineLength)
  lazy val publicMatchingEngine  = new RegexMatchingEngine(cfg.allRules.publicRules, cfg.maxLineLength)

  def scanRepository(
    repository: String,
    branch: String,
    isPrivate: Boolean,
    repositoryUrl: String,
    commitId: String,
    authorName: String,
    archiveUrl: String)(implicit hc: HeaderCarrier): Future[Report] =
    try {
      artifactService.getZipAndExplode(cfg.githubSecrets.personalAccessToken, archiveUrl, branch) match {
        case Left(BranchNotFound(_)) =>
          reportsService
            .clearReportsAfterBranchDeleted(
              DeleteBranchEvent(
                repositoryName = repository,
                authorName     = authorName,
                branchRef      = branch,
                deleted        = true,
                repositoryUrl  = repositoryUrl)
            )
            .map(_.reportSolvingProblems)
        case Right(ExplodedZip(dir)) =>
          val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine
          val processingResult =
            for {
              results <- Future { regexMatchingEngine.run(dir) }
              report = Report.create(repository, repositoryUrl, commitId, authorName, branch, results)
              _ <- reportsService.saveReport(report)
              _ <- alertingService.alert(report)
              _ <- alertAboutRepoVisibility(repoName = repository, branchName = branch, authorName, dir, isPrivate)
            } yield {
              report
            }
          processingResult.onComplete(_ => FileUtils.deleteDirectory(dir))
          processingResult
      }
    } catch {
      case NonFatal(e) => Future.failed(e)
    }

  private def alertAboutRepoVisibility(
    repoName: String,
    branchName: String,
    author: String,
    dir: File,
    isPrivate: Boolean)(implicit hc: HeaderCarrier): Future[Unit] =
    if (branchName == "master") {
      if (!repoVisibilityChecker.hasCorrectVisibilityDefined(dir, isPrivate)) {
        logger.warn(
          s"Incorrect configuration for repo $repoName on $branchName branch! File path: ${dir.getAbsolutePath}. Sending alert")
        alertingService.alertAboutRepoVisibility(repoName, author)
      } else {
        logger.info(s"repo: $repoName, branch: $branchName, dir: ${dir.getAbsolutePath}. No action needed")
        Future.successful(())
      }
    } else {
      Future.successful(())
    }

  def queueRequest(p: PayloadDetails): Future[Boolean] =
    githubRequestsQueueRepository.pushNew(p).map(_ => true)

  def scanAll(implicit ec: ExecutionContext): Future[Seq[Report]] = {
    val pullWorkItems: Enumerator[WorkItem[PayloadDetails]] =
      Enumerator.generateM(githubRequestsQueueRepository.pullOutstanding)
    val processWorkItems = Iteratee.foldM(Seq.empty[Report]) { scanOneItemAndMarkAsComplete }
    pullWorkItems.run(processWorkItems)
  }

  def scanOneItemAndMarkAsComplete(acc: Seq[Report], workItem: WorkItem[PayloadDetails]): Future[Seq[Report]] = {
    val request     = workItem.item
    implicit val hc = HeaderCarrier()
    scanRepository(
      repository    = request.repositoryName,
      branch        = request.branchRef,
      isPrivate     = request.isPrivate,
      repositoryUrl = request.repositoryUrl,
      commitId      = request.commitId,
      authorName    = request.authorName,
      archiveUrl    = request.archiveUrl
    ).flatMap(report => githubRequestsQueueRepository.complete(workItem.id).map(_ => acc :+ report))
      .recover {
        case NonFatal(e) =>
          logger.error(s"Failed scan ${request.repositoryName} on branch ${request.branchRef}", e)
          githubRequestsQueueRepository.markAs(workItem.id, ProcessingStatus.Failed)
          acc
      }
  }
}
