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

import com.google.inject.Inject
import play.api.libs.json.{Format, Json}
import reactivemongo.api.commands.WriteResult

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.leakdetection.Utils.traverseFuturesSequentially
import uk.gov.hmrc.leakdetection.model.{DeleteBranchEvent, LeakResolution, Report, ReportId}
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository

class ReportsService @Inject()(reportsRepository: ReportsRepository)(implicit ec: ExecutionContext) {

  def getRepositories = reportsRepository.getDistinctRepoNames

  def getLatestReportsForEachBranch(repoName: String): Future[List[Report]] =
    reportsRepository
      .findUnresolvedWithProblems(repoName)
      .map(_.groupBy(_.branch).map {
        case (_, reports) => reports.head
      }.toList)

  def getReport(reportId: ReportId): Future[Option[Report]] = reportsRepository.findByReportId(reportId)

  def clearCollection(): Future[WriteResult] = reportsRepository.removeAll()

  def clearReportsAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[List[Report]] = {
    import deleteBranchEvent._
    markPreviousReportsAsResolved {
      Report.create(
        repositoryName = repositoryName,
        repositoryUrl  = repositoryUrl,
        commitId       = "n/a (branch was deleted)",
        authorName     = authorName,
        branch         = branchRef,
        results        = Nil,
        leakResolution = None
      )
    }
  }

  def saveReport(report: Report): Future[Unit] = {
    def ifReportSolvesProblems(f: => Future[Unit]): Future[Unit] =
      if (report.inspectionResults.isEmpty) f else Future.successful(())

    for {
      _ <- reportsRepository.saveReport(report)
      _ <- ifReportSolvesProblems(markPreviousReportsAsResolved(report).map(_ => ()))
    } yield ()

  }

  private def markPreviousReportsAsResolved(report: Report): Future[List[Report]] = {
    val leakResolution      = LeakResolution(report.timestamp, report.commitId)
    val outstandingProblems = reportsRepository.findUnresolvedWithProblems(report.repoName, Some(report.branch))
    outstandingProblems.flatMap { reports =>
      val resolvedReports = reports.map(_.copy(leakResolution = Some(leakResolution)))
      traverseFuturesSequentially(resolvedReports)(reportsRepository.updateReport).map(_ => reports)
    }
  }
}
