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

import com.google.inject.Inject
import play.api.Configuration
import reactivemongo.api.commands.WriteResult
import uk.gov.hmrc.leakdetection.Utils.traverseFuturesSequentially
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.leakdetection.services.ReportsService.ClearingReportsResult
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ExecutionContext, Future}

class ReportsService @Inject()(
  reportsRepository: ReportsRepository,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  configuration: Configuration)(implicit ec: ExecutionContext)
    extends MetricSource {

  lazy val repositoriesToIgnore: Seq[String] =
    configuration.getOptional[Seq[String]]("shared.repositories").getOrElse(List.empty)

  def getRepositories = reportsRepository.getDistinctRepoNames

  def getLatestReportsForEachBranch(repoName: String): Future[List[Report]] =
    reportsRepository
      .findUnresolvedWithProblems(repoName)
      .map(_.groupBy(_.branch).map {
        case (_, reports) => reports.head
      }.toList)

  def getLatestReportForMaster(repoName: String): Future[Option[Report]] =
    reportsRepository
    .findUnresolvedWithProblems(repoName, Some("master")).map(_.headOption)

  def getReport(reportId: ReportId): Future[Option[Report]] =
    reportsRepository.findByReportId(reportId)

  def clearCollection(): Future[Long] =
    reportsRepository.removeAll()

  def clearReportsAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[ClearingReportsResult] = {
    import deleteBranchEvent._
    val reportSolvingProblems = Report.create(
      repositoryName = repositoryName,
      repositoryUrl  = repositoryUrl,
      commitId       = "n/a (branch was deleted)",
      authorName     = authorName,
      branch         = branchRef,
      results        = Nil,
      leakResolution = None
    )
    markPreviousReportsAsResolved(reportSolvingProblems).map { reports =>
      ClearingReportsResult(reportSolvingProblems, reports)
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
    val outstandingProblems = reportsRepository.findUnresolvedWithProblems(report.repoName, Some(report.branch)).map(_.toList)
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

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    for {
      total      <- reportsRepository.countAll()
      unresolved <- reportsRepository.howManyUnresolved()
      resolved   <- reportsRepository.howManyResolved()
      byRepo     <- reportsRepository.howManyUnresolvedByRepository()
      teams      <- teamsAndRepositoriesConnector.teamsWithRepositories()
    } yield {

      def ownedRepos(team: Team): Seq[String] = {

        val allRepos = team.repos.fold(Seq.empty[String])(_.values.toSeq.flatten)
        allRepos.filterNot(repositoriesToIgnore.contains(_))
      }

      val byTeamStats = teams
        .map(t => s"reports.teams.${t.normalisedName}.unresolved" -> ownedRepos(t).map(r => byRepo.getOrElse(r, 0)).sum)
        .toMap

      val globalStats = Map(
        "reports.total"      -> total,
        "reports.unresolved" -> unresolved,
        "reports.resolved"   -> resolved
      )

      globalStats ++ byTeamStats
    }
}

object ReportsService {
  final case class ClearingReportsResult(
    reportSolvingProblems: Report,
    previousProblems: List[Report]
  )
}
