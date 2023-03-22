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

import org.apache.commons.io.FileUtils
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors.{BranchNotFound, GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.RunMode.{Draft, Normal}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, RescanRequestsQueueRepository}
import uk.gov.hmrc.leakdetection.scanner.{ExemptionChecker, RegexMatchingEngine}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemRepository}

import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ScanningService @Inject()(
  githubConnector              : GithubConnector,
  appConfig                    : AppConfig,
  reportsService               : ReportsService,
  draftReportsService          : DraftReportsService,
  leaksService                 : LeaksService,
  alertingService              : AlertingService,
  githubRequestsQueue          : GithubRequestsQueueRepository,
  rescanRequestsQueue          : RescanRequestsQueueRepository,
  warningsService              : WarningsService,
  activeBranchesService        : ActiveBranchesService,
  exemptionChecker             : ExemptionChecker,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  lazy val privateMatchingEngine = new RegexMatchingEngine(appConfig.allRules.privateRules, appConfig.maxLineLength)
  lazy val publicMatchingEngine  = new RegexMatchingEngine(appConfig.allRules.publicRules, appConfig.maxLineLength)

  def scanRepository(
    repository:    Repository,
    branch:        Branch,
    isPrivate:     Boolean,
    isArchived:    Boolean,
    repositoryUrl: String,
    commitId:      String,
    authorName:    String,
    archiveUrl:    String,
    runMode:       RunMode
)(implicit hc: HeaderCarrier): Future[Report] = {
    val savedZipFilePath = Files.createTempFile("unzipped_", "")
    try {
      val zip = githubConnector.getZip(archiveUrl, branch, savedZipFilePath)
      val result = zip.flatMap {
        case Left(BranchNotFound(_)) =>
          val pushDelete = PushDelete(repositoryName = repository.asString, authorName = authorName, branchRef = branch.asString, repositoryUrl = repositoryUrl)
          for {
            _      <- activeBranchesService.clearBranch(pushDelete.repositoryName, pushDelete.branchRef)
            _      <- leaksService.clearBranchLeaks(pushDelete.repositoryName, pushDelete.branchRef)
            _      <- warningsService.clearBranchWarnings(pushDelete.repositoryName, pushDelete.branchRef)
            report <- reportsService.clearReportsAfterBranchDeleted(pushDelete)
          } yield report

        case Right(dir) =>
          val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine

          def executeIfDraftMode(function: => Future[Unit]): Future[Unit] = if (runMode == Draft) function else Future.unit

          def executeIfNormalMode(function: => Future[Unit]): Future[Unit] = if (runMode == Normal) function else Future.unit

          val exemptionParsingResult = RulesExemptionParser
            .parseServiceSpecificExemptions(FileAndDirectoryUtils.getSubdirName(dir))

          val exemptions = exemptionParsingResult.getOrElse(List.empty)

          for {
            matched                 <- Future {regexMatchingEngine.run(dir, exemptions)}
            results                  = matched.filterNot(_.draft)
            unusedExemptions         = exemptionChecker.run(results, exemptions)
            report                   = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, results, unusedExemptions)
            draftReport              = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, matched, unusedExemptions)
            leaks                    = Leak.createFromMatchedResults(report, results)
            warnings                 = warningsService.checkForWarnings(report, dir, isPrivate, isArchived, exemptions, exemptionParsingResult.left.toSeq)
            reportWithWarnings       = report.copy(totalWarnings = warnings.length)
            draftReportWithWarnings  = draftReport.copy(totalWarnings = warnings.length)
            _                       <- executeIfDraftMode(draftReportsService.saveReport(draftReportWithWarnings))
            _                       <- executeIfNormalMode(reportsService.saveReport(reportWithWarnings))
            _                       <- executeIfNormalMode(leaksService.saveLeaks(repository, branch, leaks))
            _                       <- executeIfNormalMode(warningsService.saveWarnings(repository, branch, warnings))
            _                       <- executeIfNormalMode(activeBranchesService.markAsActive(repository, branch, report.id))
            _                       <- executeIfNormalMode(alertingService.alert(report))
            _                       <- executeIfNormalMode(alertAboutWarnings(repository, branch, authorName, warnings))
          } yield if (runMode == Normal) reportWithWarnings else draftReportWithWarnings
      }
      result.onComplete {
        _ =>
          if(savedZipFilePath.toFile.isDirectory)
            FileUtils.deleteDirectory(savedZipFilePath.toFile)
          else
            FileUtils.delete(savedZipFilePath.toFile)
      }
      result
    } catch {
      case NonFatal(e) => Future.failed(e)
    }
  }

  private def alertAboutWarnings(
    repository: Repository,
    branch: Branch,
    author: String,
    warnings: Seq[Warning]
  )(implicit hc: HeaderCarrier): Future[Unit] =
    teamsAndRepositoriesConnector
      .repo(repository.asString)
      .map(_.map(repo =>
        if (branch.asString == repo.defaultBranch)
          alertingService.alertAboutWarnings(author, warnings)
      ))

  def queueRequest(p: PushUpdate): Future[Boolean] =
    githubRequestsQueue.pushNew(p).map(_ => true)

  def queueRescanRequest(p: PushUpdate): Future[Boolean] =
    rescanRequestsQueue.pushNew(p).map(_ => true)

  def scanAll(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Int] = {
    def processNext(acc: Int): Future[Int] =
      githubRequestsQueue.pullOutstanding.flatMap {
        case None     => Future.successful(acc)
        case Some(wi) => scanOneItemAndMarkAsComplete(githubRequestsQueue)(wi).flatMap(res => processNext(acc + res.size))
      }

    for {
      scanned   <- processNext(0)
      rescanned <- rescanOne // limit rescanning to a single repo per cycle
    } yield  scanned + rescanned
  }

  private def rescanOne(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Int] =
    rescanRequestsQueue.pullOutstanding.flatMap {
      case None     => Future.successful(0)
      case Some(wi) => scanOneItemAndMarkAsComplete(rescanRequestsQueue)(wi).map(_.size)
    }

  private val maxRetries = appConfig.maxRetries
  private def scanOneItemAndMarkAsComplete(repo:WorkItemRepository[PushUpdate])(workItem: WorkItem[PushUpdate])
      (implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Option[Report]] =
    (for {
      report <- scanRepository(
                  repository    = Repository(workItem.item.repositoryName)
                , branch        = Branch(workItem.item.branchRef)
                , isPrivate     = workItem.item.isPrivate
                , isArchived    = workItem.item.isArchived
                , repositoryUrl = workItem.item.repositoryUrl
                , commitId      = workItem.item.commitId
                , authorName    = workItem.item.authorName
                , archiveUrl    = workItem.item.archiveUrl
                , runMode       = workItem.item.runMode.getOrElse(Normal)
                )
      _      <- repo.completeAndDelete(workItem.id)
    } yield
      Some(report)
    ).recoverWith {
      case e: java.util.concurrent.TimeoutException if workItem.failureCount < maxRetries =>
        logger.error(s"Failed scan ${workItem.item.repositoryName} on branch ${workItem.item.branchRef} attempt ${workItem.failureCount}/$maxRetries", e)
        repo.markAs(workItem.id, ProcessingStatus.Failed).map(_ => None)
      case e: java.util.concurrent.TimeoutException =>
        logger.error(s"Failed scan ${workItem.item.repositoryName} on branch ${workItem.item.branchRef} last attempt ${workItem.failureCount}/$maxRetries - alerting on slack", e)
        val commitInfo = CommitInfo(
          repository    = Repository(workItem.item.repositoryName)
        , branch        = Branch(workItem.item.branchRef)
        , author        = workItem.item.authorName
        )
        for {
          _ <- alertingService.alertLastScanAttempt(commitInfo)
          _ <- repo.markAs(workItem.id, ProcessingStatus.PermanentlyFailed).map(_ => None)
        } yield None
      case NonFatal(e) =>
        logger.error(s"Failed scan ${workItem.item.repositoryName} on branch ${workItem.item.branchRef} - unexpected error", e)
        repo.markAs(workItem.id, ProcessingStatus.Failed).map(_ => None)
    }
}
