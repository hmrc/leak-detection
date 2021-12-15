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

import org.apache.commons.io.FileUtils
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.GithubRequestsQueueRepository
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.leakdetection.services.ArtifactService.BranchNotFound
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}

import java.io.File
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ScanningService @Inject()(
  artifactService: ArtifactService,
  configLoader: ConfigLoader,
  reportsService: ReportsService,
  alertingService: AlertingService,
  githubRequestsQueueRepository: GithubRequestsQueueRepository,
  repoVisibilityChecker: RepoVisiblityChecker,
  githubService: GithubService
)(implicit ec: ExecutionContext) {

  import configLoader.cfg

  private val logger = Logger(getClass)

  lazy val privateMatchingEngine = new RegexMatchingEngine(cfg.allRules.privateRules, cfg.maxLineLength)
  lazy val publicMatchingEngine  = new RegexMatchingEngine(cfg.allRules.publicRules, cfg.maxLineLength)

  def scanRepository(
    repository: Repository,
    branch: Branch,
    isPrivate: Boolean,
    repositoryUrl: String,
    commitId: String,
    authorName: String,
    archiveUrl: String)(implicit hc: HeaderCarrier): Future[Report] =
    try {
      artifactService.getZip(cfg.githubSecrets.personalAccessToken, archiveUrl, branch) flatMap {
        case Left(BranchNotFound(_)) =>
          reportsService
            .clearReportsAfterBranchDeleted(
              DeleteBranchEvent(
                repositoryName = repository.asString,
                authorName     = authorName,
                branchRef      = branch.asString,
                deleted        = true,
                repositoryUrl  = repositoryUrl)
            )
            .map(_.reportSolvingProblems)
        case Right(dir) =>
          val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine
          val processingResult =
            for {
              results <- Future { regexMatchingEngine.run(dir) }
              report = Report.create(repository.asString, repositoryUrl, commitId, authorName, branch.asString, results)
              _ <- reportsService.saveReport(report)
              _ <- alertingService.alert(report)
              _ <- alertAboutRepoVisibility(repository = repository, branch = branch, authorName, dir, isPrivate)
              _ <- alertAboutExemptionWarnings(repository, branch, authorName, dir)
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
                                        repository: Repository,
                                        branch: Branch,
                                        author: String,
                                        dir: File,
                                        isPrivate: Boolean)(implicit hc: HeaderCarrier): Future[Unit] =
    githubService.getDefaultBranchName(repository) flatMap { defaultBranchName =>
      if (branch == defaultBranchName) {
        if (!repoVisibilityChecker.hasCorrectVisibilityDefined(dir, isPrivate)) {
          logger.warn(
            s"Incorrect configuration for repo ${repository.asString} on ${branch.asString} branch! File path: ${dir.getAbsolutePath}. Sending alert")
          alertingService.alertAboutRepoVisibility(repository, author)
        } else {
          logger.info(s"repo: ${repository.asString}, branch: ${branch.asString}, dir: ${dir.getAbsolutePath}. No action needed")
          Future.unit
        }
      } else {
        Future.unit
      }
    }

  private def alertAboutExemptionWarnings(
                                    repository: Repository,
                                    branch: Branch,
                                    author: String,
                                    dir: File)(implicit hc: HeaderCarrier): Future[Unit] =
    githubService.getDefaultBranchName(repository) flatMap { defaultBranch =>
    if (branch == defaultBranch) {
      val exemptions = RulesExemptionParser.parseServiceSpecificExemptions(FileAndDirectoryUtils.getSubdirName(dir))

      if(exemptions.exists(!_.text.isDefined)) {
          alertingService.alertAboutExemptionWarnings(repository, defaultBranch, author)
      }
    }
    Future.unit
  }

  def queueRequest(p: PayloadDetails): Future[Boolean] =
    githubRequestsQueueRepository.pushNew(p).map(_ => true)

  def scanAll(implicit ec: ExecutionContext): Future[Int] = {
    def processNext(acc: Int): Future[Int] =
      githubRequestsQueueRepository.pullOutstanding.flatMap {
        case None     => Future.successful(acc)
        case Some(wi) => scanOneItemAndMarkAsComplete(wi).flatMap(res => processNext(acc + res.size))
      }

    processNext(0)
  }

  def scanOneItemAndMarkAsComplete(workItem: WorkItem[PayloadDetails]): Future[Option[Report]] = {
    val request     = workItem.item
    implicit val hc = HeaderCarrier()
    scanRepository(
      repository    = Repository(request.repositoryName),
      branch        = Branch(request.branchRef),
      isPrivate     = request.isPrivate,
      repositoryUrl = request.repositoryUrl,
      commitId      = request.commitId,
      authorName    = request.authorName,
      archiveUrl    = request.archiveUrl
    ).flatMap(report => githubRequestsQueueRepository.completeAndDelete(workItem.id).map(_ => Some(report)))
      .recoverWith {
        case NonFatal(e) =>
          logger.error(s"Failed scan ${request.repositoryName} on branch ${request.branchRef}", e)
          githubRequestsQueueRepository.markAs(workItem.id, ProcessingStatus.Failed).map(_ => None)
      }
  }
}
