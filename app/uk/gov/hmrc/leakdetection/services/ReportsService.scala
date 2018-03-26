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
import play.api.Logger
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json.{Format, Json}
import reactivemongo.api.commands.WriteResult

import scala.concurrent.{Await, ExecutionContext, Future}
import uk.gov.hmrc.leakdetection.Utils.traverseFuturesSequentially
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.leakdetection.persistence.OldReportsRepository

import scala.util.{Failure, Success}
import scala.util.control.NonFatal

class ReportsService @Inject()(reportsRepository: ReportsRepository, oldReportsRepository: OldReportsRepository)(
  implicit ec: ExecutionContext) {

  import ReportsService._

  def getRepositories = reportsRepository.getDistinctRepoNames

  def getLatestReportsForEachBranch(repoName: String): Future[List[Report]] =
    reportsRepository
      .findUnresolvedWithProblems(repoName)
      .map(_.groupBy(_.branch).map {
        case (_, reports) => reports.head
      }.toList)

  def getReport(reportId: ReportId): Future[Option[Report]] = reportsRepository.findByReportId(reportId)

  def clearCollection(): Future[WriteResult] = reportsRepository.removeAll()

  def clearReportsAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[ClearedReportsInfo] = {
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
    }.map { resolvedReports =>
      ClearedReportsInfo(repositoryName, branchRef, resolvedReports.map(_._id))
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
    val outstandingProblems = reportsRepository.findUnresolvedWithProblems(report.repoName, Some(report.branch))
    outstandingProblems.flatMap { unresolvedReports =>
      val resolvedReports = unresolvedReports.map { unresolvedReport =>
        val leakResolution =
          LeakResolution(
            timestamp = report.timestamp,
            commitId  = report.commitId,
            resolvedLeaks = unresolvedReport.inspectionResults.map { reportLine =>
              ResolvedLeak(ruleId = reportLine.ruleId.getOrElse(""), description = reportLine.description)
            }
          )
        unresolvedReport.copy(leakResolution = Some(leakResolution), inspectionResults = Nil)
      }
      traverseFuturesSequentially(resolvedReports)(reportsRepository.updateReport).map(_ => unresolvedReports)
    }
  }

  def fixPreviousResolvedReports(): Future[Unit] = {
    val unresolvedEnumerator: Enumerator[OldReport] = oldReportsRepository.findPreviouslyResolvedOldReports()

    import scala.concurrent.duration._

    var c = 1

    val process2 =
      Iteratee.foreach[OldReport] { oldReport =>
        val updatedLeakResolution = oldReport.leakResolution.map { oldLeakResolution =>
          LeakResolution(
            timestamp = oldLeakResolution.timestamp,
            commitId  = oldLeakResolution.commitId,
            resolvedLeaks = oldReport.inspectionResults.map { reportLine =>
              ResolvedLeak(ruleId = reportLine.ruleId.getOrElse(""), description = reportLine.description)
            }
          )
        }

        val updatedReport = Report(
          _id               = oldReport._id,
          repoName          = oldReport.repoName,
          repoUrl           = oldReport.repoUrl,
          commitId          = oldReport.commitId,
          branch            = oldReport.branch,
          timestamp         = oldReport.timestamp,
          author            = oldReport.author,
          inspectionResults = Nil,
          leakResolution    = updatedLeakResolution
        )

        if (c % 1000 == 0) {
          Logger.info(
            s"Fixing report $c, old was: $oldReport, updated is $updatedReport"
          )
        }

        Await.result(reportsRepository.updateReport(updatedReport), 10.seconds)
        c = c + 1
      }

    unresolvedEnumerator.run(process2).andThen {
      case Success(_)            => Logger.info(s"All reports updated, last counter was: $c")
      case Failure(NonFatal(ex)) => Logger.error(s"Error fixing previous reports", ex)
    }

  }
}

object ReportsService {

  final case class ClearedReportsInfo(
    repoName: String,
    branchName: String,
    clearedReports: List[ReportId]
  )

  object ClearedReportsInfo {
    implicit val format: Format[ClearedReportsInfo] = Json.format[ClearedReportsInfo]
  }

}
