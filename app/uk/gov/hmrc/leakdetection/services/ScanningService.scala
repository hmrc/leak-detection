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

import org.apache.commons.io.FileUtils
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, RescanRequestsQueueRepository}
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.leakdetection.services.ArtifactService.BranchNotFound
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemRepository}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ScanningService @Inject()(
                                 artifactService:       ArtifactService,
                                 configLoader:          ConfigLoader,
                                 reportsService:        ReportsService,
                                 draftReportsService:   DraftReportsService,
                                 leaksService:          LeaksService,
                                 alertingService:       AlertingService,
                                 githubRequestsQueue:   GithubRequestsQueueRepository,
                                 rescanRequestsQueue:   RescanRequestsQueueRepository,
                                 warningsService:       WarningsService,
                                 githubService:         GithubService
)(implicit ec: ExecutionContext) {

  import configLoader.cfg

  private val logger = Logger(getClass)

  lazy val privateMatchingEngine = new RegexMatchingEngine(cfg.allRules.privateRules, cfg.maxLineLength)
  lazy val publicMatchingEngine  = new RegexMatchingEngine(cfg.allRules.publicRules, cfg.maxLineLength)

  def scanRepository(
    repository:    Repository,
    branch:        Branch,
    isPrivate:     Boolean,
    repositoryUrl: String,
    commitId:      String,
    authorName:    String,
    archiveUrl:    String,
    dryRun:        Boolean
  )(implicit hc: HeaderCarrier): Future[Report] =
    try {
      artifactService.getZip(cfg.githubSecrets.personalAccessToken, archiveUrl, branch).flatMap {
        case Left(BranchNotFound(_)) =>
          val deleteBranchEvent = DeleteBranchEvent(repositoryName = repository.asString, authorName = authorName, branchRef = branch.asString, deleted = true, repositoryUrl = repositoryUrl)
          for {
            _      <- leaksService.clearLeaksAfterBranchDeleted(deleteBranchEvent)
            report <- reportsService.clearReportsAfterBranchDeleted(deleteBranchEvent)
          } yield report

        case Right(dir) =>
          val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine
          def executeIfNotDryRun(function: => Future[Unit]): Future[Unit] = if (!dryRun) function else Future.successful(Unit)

          val processingResult =
            for {
              matched           <- Future { regexMatchingEngine.run(dir) }
              (drafts, results)  = matched.partition(_.draft)
              report             = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, results)
              draftReport        = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, drafts)
              leaks              = Leak.createFromMatchedResults(report, results)
              warnings           = warningsService.checkForWarnings(report, dir, isPrivate)
              _                 <- if(draftReport.totalLeaks > 0) draftReportsService.saveReport(draftReport) else Future.unit
              _                 <- executeIfNotDryRun(reportsService.saveReport(report))
              _                 <- executeIfNotDryRun(leaksService.saveLeaks(repository, branch, leaks))
              _                 <- executeIfNotDryRun(warningsService.saveWarnings(repository, branch, warnings))
              _                 <- executeIfNotDryRun(alertingService.alert(report))
              _                 <- executeIfNotDryRun(alertAboutWarnings(repository, branch, authorName, dir.getAbsolutePath, warnings))
            } yield report

          processingResult.onComplete(_ => FileUtils.deleteDirectory(dir))
          processingResult
      }
    } catch {
      case NonFatal(e) => Future.failed(e)
    }

  private def alertAboutWarnings(
                                  repository: Repository,
                                  branch: Branch,
                                  author: String,
                                  absolutePath: String,
                                  warnings: Seq[Warning])(implicit hc: HeaderCarrier): Future[Unit] =
    githubService.getDefaultBranchName(repository) flatMap { defaultBranchName =>
      if (branch == defaultBranchName) {

        if (warnings.map(_.warningMessageType).intersect(Seq(MissingRepositoryYamlFile.toString, InvalidEntry.toString, MissingEntry.toString, ParseFailure.toString)).nonEmpty) {
          logger.warn(s"Incorrect configuration for repo ${repository.asString} on ${branch.asString} branch! File path: $absolutePath. Sending alert")
          alertingService.alertAboutRepoVisibility(repository, author)
        }
        if (warnings.map(_.warningMessageType).contains(FileLevelExemptions.toString)) {
          alertingService.alertAboutExemptionWarnings(repository, branch, author)
        }
      }
      Future.unit
    }

  def queueRequest(p: PayloadDetails): Future[Boolean] =
    githubRequestsQueue.pushNew(p).map(_ => true)

  def queueRescanRequest(p: PayloadDetails): Future[Boolean] =
    rescanRequestsQueue.pushNew(p).map(_ => true)

  def scanAll(implicit ec: ExecutionContext): Future[Int] = {
    def processNext(acc: Int): Future[Int] =
      githubRequestsQueue.pullOutstanding.flatMap {
        case None     => Future.successful(acc)
        case Some(wi) => scanOneItemAndMarkAsComplete(githubRequestsQueue)(wi, dryRun = false).flatMap(res => processNext(acc + res.size))
      }

    for {
      scanned   <- processNext(0)
      rescanned <- rescanOne // limit rescanning to a single repo per cycle
    } yield  scanned + rescanned
  }

  def rescanOne(implicit ec: ExecutionContext): Future[Int] = {
    rescanRequestsQueue.pullOutstanding.flatMap {
      case None     => Future.successful(0)
      case Some(wi) => scanOneItemAndMarkAsComplete(rescanRequestsQueue)(wi, dryRun = true).map(_.size)
    }
  }

  def scanOneItemAndMarkAsComplete(repo:WorkItemRepository[PayloadDetails])(workItem: WorkItem[PayloadDetails], dryRun: Boolean): Future[Option[Report]] = {
    val request     = workItem.item
    implicit val hc = HeaderCarrier()
    scanRepository(
      repository    = Repository(request.repositoryName),
      branch        = Branch(request.branchRef),
      isPrivate     = request.isPrivate,
      repositoryUrl = request.repositoryUrl,
      commitId      = request.commitId,
      authorName    = request.authorName,
      archiveUrl    = request.archiveUrl,
      dryRun        = dryRun
    ).flatMap(report => repo.completeAndDelete(workItem.id).map(_ => Some(report)))
      .recoverWith {
        case NonFatal(e) =>
          logger.error(s"Failed scan ${request.repositoryName} on branch ${request.branchRef}", e)
          repo.markAs(workItem.id, ProcessingStatus.Failed).map(_ => None)
      }
  }

}