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
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors.{GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.RunMode.{Draft, Normal}
import uk.gov.hmrc.leakdetection.model.*
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, RescanRequestsQueueRepository}
import uk.gov.hmrc.leakdetection.scanner.{ExemptionChecker, MatchedResult, RegexMatchingEngine}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemRepository}

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeoutException
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
)(using ExecutionContext) extends Logging:

  lazy val privateMatchingEngine = RegexMatchingEngine(appConfig.allRules.privateRules, appConfig.maxLineLength)
  lazy val publicMatchingEngine  = RegexMatchingEngine(appConfig.allRules.publicRules, appConfig.maxLineLength)

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
  )(using HeaderCarrier): Future[Report] =
    val savedZipFilePath = Files.createTempFile("unzipped_", "")
    try
      val zip = githubConnector.getZip(archiveUrl, branch, savedZipFilePath)
      val result = zip.flatMap:
        case Left(GithubConnector.BranchNotFound(_)) =>
          val pushDelete = PushDelete(repositoryName = repository.asString, authorName = authorName, branchRef = branch.asString, repositoryUrl = repositoryUrl)
          for {
            _      <- activeBranchesService.clearBranch(pushDelete.repositoryName, pushDelete.branchRef)
            _      <- leaksService.clearBranchLeaks(pushDelete.repositoryName, pushDelete.branchRef)
            _      <- warningsService.clearBranchWarnings(pushDelete.repositoryName, pushDelete.branchRef)
            _      <- rescanRequestsQueue.delete(pushDelete.repositoryName, pushDelete.branchRef)
            report <- reportsService.clearReportsAfterBranchDeleted(pushDelete)
          } yield report
        case Right(dir) =>
          def executeIfDraftMode(function: => Future[Unit] ): Future[Unit] = if runMode == Draft  then function else Future.unit
          def executeIfNormalMode(function: => Future[Unit]): Future[Unit] = if runMode == Normal then function else Future.unit

          val regexMatchingEngine = if isPrivate then privateMatchingEngine else publicMatchingEngine
          val exemptionParsingResult = RulesExemptionParser
            .parseServiceSpecificExemptions(FileAndDirectoryUtils.getSubdirName(dir))

          val exemptions = exemptionParsingResult.getOrElse(List.empty)

          for
            matched                 <- Future {regexMatchingEngine.run(dir, exemptions)}
            results                  = matched.filterNot(_.draft)
            resultsWithCommitId     <- addCommitId(repository, branch, results)
            unusedExemptions         = exemptionChecker.run(resultsWithCommitId, exemptions)
            report                   = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, resultsWithCommitId, unusedExemptions)
            draftReport              = Report.createFromMatchedResults(repository.asString, repositoryUrl, commitId, authorName, branch.asString, matched, unusedExemptions)
            leaks                    = Leak.createFromMatchedResults(report, resultsWithCommitId)
            warnings                 = warningsService.checkForWarnings(report, dir, isPrivate, isArchived, exemptions, exemptionParsingResult.left.toSeq)
            reportWithWarnings       = report.copy(totalWarnings = warnings.length)
            draftReportWithWarnings  = draftReport.copy(totalWarnings = warnings.length)
            _                       <- executeIfDraftMode(draftReportsService.saveReport(draftReportWithWarnings))
            _                       <- executeIfNormalMode(reportsService.saveReport(reportWithWarnings))
            _                       <- executeIfNormalMode(leaksService.saveLeaks(repository, branch, leaks))
            _                       <- executeIfNormalMode(warningsService.saveWarnings(repository, branch, warnings))
            _                       <- executeIfNormalMode(activeBranchesService.markAsActive(repository, branch, report.id))
            _                       <- executeIfNormalMode(alertingService.alert(report, isPrivate))
            _                       <- executeIfNormalMode(whenDefaultBranch(repository, branch)(alertingService.alertAboutWarnings(authorName, warnings, isPrivate)))
          yield if runMode == Normal then reportWithWarnings else draftReportWithWarnings
      result.onComplete:
        _ =>
          if(savedZipFilePath.toFile.isDirectory)
            FileUtils.deleteDirectory(savedZipFilePath.toFile)
          else
            FileUtils.delete(savedZipFilePath.toFile)
      result
    catch
      case NonFatal(e) => Future.failed(e)

  private def whenDefaultBranch(repository: Repository, branch: Branch)(f: => Future[Unit]): Future[Unit] =
    teamsAndRepositoriesConnector
      .repo(repository.asString)
      .flatMap:
        case Some(repo) if repo.defaultBranch == branch.asString => f
        case _                                                   => Future.unit

  def queueDistinctRequest(p: PushUpdate): Future[Boolean] =
    for
      itemAlreadyQueued <- githubRequestsQueue.findByCommitIdAndBranch(p).map(_.isDefined)
      reportExists      <- reportsService.reportExists(p)
      duplicate          = itemAlreadyQueued || reportExists
      _                 <- if !duplicate then githubRequestsQueue.pushNew(p) else Future.unit
    yield duplicate

  def scanAll(using ExecutionContext): Future[Int] =
    def processNext(acc: Int): Future[Int] =
      githubRequestsQueue.pullOutstanding.flatMap:
        case None     => Future.successful(acc)
        case Some(wi) => scanOneItemAndMarkAsComplete(githubRequestsQueue)(wi).flatMap(res => processNext(acc + res.size))

    for
      scanned   <- processNext(0)
      rescanned <- rescanOne // limit rescanning to a single repo per cycle
    yield  scanned + rescanned

  private def rescanOne(using ExecutionContext): Future[Int] =
    rescanRequestsQueue.pullOutstanding.flatMap:
      case None     => Future.successful(0)
      case Some(wi) => scanOneItemAndMarkAsComplete(rescanRequestsQueue)(wi).map(_.size)

  import org.apache.pekko.stream.IOOperationIncompleteException
  private def scanOneItemAndMarkAsComplete(repo:WorkItemRepository[PushUpdate])(workItem: WorkItem[PushUpdate]): Future[Option[Report]] =
    val request     = workItem.item
    given HeaderCarrier = HeaderCarrier()
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
     .recoverWith:
       case e: IOOperationIncompleteException if e.getCause.isInstanceOf[GithubConnector.LargeDownloadException] =>
         val repository = Repository(request.repositoryName)
         val branch     = Branch(request.branchRef)
         for
           _ <- whenDefaultBranch(repository, branch)(alertingService.alertAboutFailure(repository, branch, request.authorName, e.getCause.getMessage, request.isPrivate))
           _ <- repo.markAs(workItem.id, ProcessingStatus.PermanentlyFailed)
           _ =  logger.error(s"Failed scan due to large download exception - repo: ${request.repositoryName}, branch: ${request.branchRef}, commit: ${request.commitId}", e)
         yield None
       case e: IOOperationIncompleteException if e.getCause.isInstanceOf[TimeoutException] =>
         val backOffMillis = Math.min(workItem.failureCount * appConfig.timeoutBackoff.toMillis, appConfig.timeoutBackOffMax.toMillis)
         if workItem.failureCount > 2 then
           logger.error(s"Failed scan due to timeouts - repo: ${request.repositoryName}, branch: ${request.branchRef}, commit: ${request.commitId}, retry count: ${workItem.failureCount}, timeoutMillis: $backOffMillis", e)
         repo.markAs(workItem.id, ProcessingStatus.Failed, Some(Instant.now().plusMillis(backOffMillis))).map(_ => None)
       case NonFatal(e) =>
         logger.error(s"Failed scan - repo: ${request.repositoryName}, branch: ${request.branchRef}, commit: ${request.commitId}, retry count: ${workItem.failureCount}", e)
         repo.markAs(workItem.id, ProcessingStatus.Failed).map(_ => None)

  private def addCommitId(
      repository: Repository,
      branch: Branch,
      results: List[MatchedResult]): Future[List[MatchedResult]] =
    for
      blameToFileSeq <- getBlameByFile(repository, branch, results)
      blameToFileMap = blameToFileSeq.toMap
      resultsWithCommitID = results.map: r =>
        val commitIds = getCommitIdForLine(r.lineNumber, blameToFileMap.get(r.filePath))
        r.copy(commitId = commitIds)
    yield resultsWithCommitID

  private def getBlameByFile(
      repository: Repository,
      branch: Branch,
      results: List[MatchedResult]): Future[Seq[(String, GitBlame)]] =
    val files: Set[String] =
      results.map(_.filePath).toSet
    val fileToBlameTuple: Seq[(String, Future[GitBlame])] =
      files.map(f => (f -> githubConnector.getBlame(repository, branch, f))).toSeq
    Future.sequence(
      fileToBlameTuple.map { case (s1, fut_s2) => fut_s2.map { s2 => (s1, s2) } }
    )

  private def getCommitIdForLine(lineNumber: Int, blame: Option[GitBlame]): Option[String] =
    blame match
      case Some(value) =>
        val found = value.ranges.find(range => range.startingLine >= lineNumber && range.endingLine <= lineNumber)
        found.map(_.oid)
      case None =>
        None

