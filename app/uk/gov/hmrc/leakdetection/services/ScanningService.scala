/*
 * Copyright 2018 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}

import org.apache.commons.io.FileUtils
import play.api.{Configuration, Logger}
import play.api.libs.iteratee.{Enumerator, Iteratee}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{PayloadDetails, Report}
import uk.gov.hmrc.leakdetection.persistence.GithubRequestsQueueRepository
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.workitem.{Failed, WorkItem}
@Singleton
class ScanningService @Inject()(
  configuration: Configuration,
  artifactService: ArtifactService,
  configLoader: ConfigLoader,
  reportsService: ReportsService,
  alertingService: AlertingService,
  githubRequestsQueueRepository: GithubRequestsQueueRepository
) {

  import configLoader.cfg

  lazy val privateMatchingEngine = new RegexMatchingEngine(cfg.allRules.privateRules, cfg.maxLineLength)
  lazy val publicMatchingEngine  = new RegexMatchingEngine(cfg.allRules.publicRules, cfg.maxLineLength)

  def now = DateTimeUtils

  def scanRepository(
    repository: String,
    branch: String,
    isPrivate: Boolean,
    repositoryUrl: String,
    commitId: String,
    authorName: String,
    archiveUrl: String)(implicit hc: HeaderCarrier): Future[Report] =
    try {
      val explodedZipDir = artifactService
        .getZipAndExplode(cfg.githubSecrets.personalAccessToken, archiveUrl, branch)

      try {
        val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine
        val results             = regexMatchingEngine.run(explodedZipDir)
        val report              = Report.create(repository, repositoryUrl, commitId, authorName, branch, results)
        for {
          _ <- reportsService.saveReport(report)
          _ <- alertingService.alert(report)
        } yield {
          report
        }
      } finally {
        FileUtils.deleteDirectory(explodedZipDir)
      }
    } catch {
      case e: RuntimeException => Future.failed(e)
    }

  def queueRequest(p: PayloadDetails)(implicit hc: HeaderCarrier): Future[Boolean] =
    githubRequestsQueueRepository.pushNew(p, DateTimeUtils.now).map(_ => true)

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
        case e =>
          Logger.error(s"Failed scan ${request.repositoryName} on branch ${request.branchRef}", e)
          githubRequestsQueueRepository.markAs(workItem.id, Failed)
          acc
      }
  }
}
