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
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors.{BranchNotFound, GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.RunMode.{Draft, Normal}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, RescanRequestsQueueRepository}
import uk.gov.hmrc.leakdetection.scanner.{ExemptionChecker, RegexMatchingEngine}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemRepository}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.Try

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
  )(implicit hc: HeaderCarrier): Future[Report] =
    try {
      githubConnector.getZip(archiveUrl, branch).flatMap {
        case Left(BranchNotFound(_)) =>
          val deleteBranchEvent = DeleteBranchEvent(repositoryName = repository.asString, authorName = authorName, branchRef = branch.asString, deleted = true, repositoryUrl = repositoryUrl)
          for {
            _      <- activeBranchesService.clearBranch(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
            _      <- leaksService.clearBranchLeaks(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
            _      <- warningsService.clearBranchWarnings(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
            report <- reportsService.clearReportsAfterBranchDeleted(deleteBranchEvent)
          } yield report

        case Right(dir) =>
          val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine
          def executeIfDraftMode(function: => Future[Unit]): Future[Unit] = if (runMode == Draft) function else Future.unit
          def executeIfNormalMode(function: => Future[Unit]): Future[Unit] = if (runMode == Normal) function else Future.unit

          val processingResult =
            for {
              matched                <- Future { regexMatchingEngine.run(dir) }
              results                 = matched.filterNot(_.draft)
              unusedExemptions        = exemptionChecker.checkForUnused(results, dir)
              report                  = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, results, unusedExemptions)
              draftReport             = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, matched, unusedExemptions)
              leaks                   = Leak.createFromMatchedResults(report, results)
              warnings                = warningsService.checkForWarnings(report, dir, isPrivate, isArchived)
              reportWithWarnings      = report.copy(totalWarnings = warnings.length)
              draftReportWithWarnings = draftReport.copy(totalWarnings = warnings.length)
              _                      <- executeIfDraftMode(draftReportsService.saveReport(draftReportWithWarnings))
              _                      <- executeIfNormalMode(reportsService.saveReport(reportWithWarnings))
              _                      <- executeIfNormalMode(leaksService.saveLeaks(repository, branch, leaks))
              _                      <- executeIfNormalMode(warningsService.saveWarnings(repository, branch, warnings))
              _                      <- executeIfNormalMode(activeBranchesService.markAsActive(repository, branch, report.id))
              _                      <- executeIfNormalMode(alertingService.alert(report))
              _                      <- executeIfNormalMode(alertAboutWarnings(repository, branch, authorName, warnings))
            } yield if (runMode == Normal) reportWithWarnings else draftReportWithWarnings

          processingResult.onComplete { _ =>
            Try(FileUtils.deleteDirectory(dir))
              .fold(ex => logger.error(s"Could not delete directory $dir", ex), result => result)
          }
          processingResult
      }
    } catch {
      case NonFatal(e) => Future.failed(e)
    }

  private def alertAboutWarnings(
    repository: Repository,
    branch: Branch,
    author: String,
    warnings: Seq[Warning]
  )(implicit hc: HeaderCarrier): Future[Unit] =
    teamsAndRepositoriesConnector.repo(repository.asString).map(_.map(repo =>
      if (branch.asString == repo.defaultBranch) {
        alertingService.alertAboutWarnings(author, warnings)
      }
    ))

  def queueRequest(p: PayloadDetails): Future[Boolean] =
    githubRequestsQueue.pushNew(p).map(_ => true)

  def queueRescanRequest(p: PayloadDetails): Future[Boolean] =
    rescanRequestsQueue.pushNew(p).map(_ => true)

  def scanAll(implicit ec: ExecutionContext): Future[Int] = {
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

  def rescanOne(implicit ec: ExecutionContext): Future[Int] =
    rescanRequestsQueue.pullOutstanding.flatMap {
      case None     => Future.successful(0)
      case Some(wi) => scanOneItemAndMarkAsComplete(rescanRequestsQueue)(wi).map(_.size)
    }

  def scanOneItemAndMarkAsComplete(repo:WorkItemRepository[PayloadDetails])(workItem: WorkItem[PayloadDetails]): Future[Option[Report]] = {
    val request     = workItem.item
    implicit val hc = HeaderCarrier()
    scanRepository(
      repository    = Repository(request.repositoryName),
      branch        = Branch(request.branchRef),
      isPrivate     = request.isPrivate,
      isArchived    = request.isArchived,
      repositoryUrl = request.repositoryUrl,
      commitId      = request.commitId,
      authorName    = request.authorName,
      archiveUrl    = request.archiveUrl,
      runMode       = request.runMode.getOrElse(Normal)
    ).flatMap(report => repo.completeAndDelete(workItem.id).map(_ => Some(report)))
      .recoverWith {
        case NonFatal(e) =>
          logger.error(s"Failed scan ${request.repositoryName} on branch ${request.branchRef}", e)
          repo.markAs(workItem.id, ProcessingStatus.Failed).map(_ => None)
      }
  }

}
