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

import com.google.inject.Inject
import play.api.Configuration
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository

import scala.concurrent.{ExecutionContext, Future}

class ReportsService @Inject()(
  reportsRepository: ReportsRepository,
  configuration    : Configuration
)(using ExecutionContext):

  lazy val repositoriesToIgnore: Seq[String] =
    configuration.get[Seq[String]]("shared.repositories")

  def getLatestReport(repository: Repository, branch: Branch): Future[Option[Report]] =
    reportsRepository
      .findLatestReport(repository, branch)

  def getReport(reportId: ReportId): Future[Option[Report]] =
    reportsRepository.findByReportId(reportId)

  def clearReportsAfterBranchDeleted(pushDelete: PushDelete): Future[Report] =
    val reportSolvingProblems: Report =
      Report.createFromMatchedResults(
        repositoryName = pushDelete.repositoryName,
        repositoryUrl  = pushDelete.repositoryUrl,
        commitId       = "n/a (branch was deleted)",
        authorName     = pushDelete.authorName,
        branch         = pushDelete.branchRef,
        results        = Nil,
        unusedExemptions = Nil
      )
    for
      _  <- reportsRepository.saveReport(reportSolvingProblems)
    yield  reportSolvingProblems

  def saveReport(report: Report): Future[Unit] =
    reportsRepository.saveReport(report)

  def reportExists(pushUpdate: PushUpdate): Future[Boolean] =
    reportsRepository.findByCommitIdAndBranch(pushUpdate.commitId, pushUpdate.branchRef).map(_.isDefined)
