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

import com.google.inject.Inject
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.Utils.traverseFuturesSequentially
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.{ReportsRepository, LeakRepository}
import uk.gov.hmrc.leakdetection.services.ReportsService.ClearingReportsResult
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ExecutionContext, Future}

class ReportsService @Inject()(
                                reportsRepository: ReportsRepository,
                                leakRepository: LeakRepository,
                                teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
                                configuration: Configuration,
                                githubService: GithubService)(implicit ec: ExecutionContext)
    extends MetricSource {

  lazy val repositoriesToIgnore: Seq[String] =
    configuration.getOptional[Seq[String]]("shared.repositories").getOrElse(List.empty)

  def getRepositories = reportsRepository.getDistinctRepoNames

  def getLatestReportsForEachBranch(repository: Repository): Future[List[Report]] =
    reportsRepository
      .findUnresolvedWithProblems(repository)
      .map(_.groupBy(_.branch).map {
        case (_, reports) => reports.head
      }.toList)

  def getLatestReportForDefaultBranch(repository: Repository)(implicit hc: HeaderCarrier): Future[Option[Report]] = {
    githubService.getDefaultBranchName(repository) flatMap { defaultBranchName =>
      reportsRepository
        .findUnresolvedWithProblems(repository, Some(defaultBranchName)).map(_.headOption)
    }
  }

  def getReport(reportId: ReportId): Future[Option[Report]] =
    reportsRepository.findByReportId(reportId)

  def clearReportsAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[ClearingReportsResult] = {
    val reportSolvingProblems = Report.create(
      repositoryName =  deleteBranchEvent.repositoryName,
      repositoryUrl  =  deleteBranchEvent.repositoryUrl,
      commitId       = "n/a (branch was deleted)",
      authorName     =  deleteBranchEvent.authorName,
      branch         =  deleteBranchEvent.branchRef,
      results        = Nil,
      leakResolution = None
    )
    for {
      _      <- leakRepository.removeBranch(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
      result <- markPreviousReportsAsResolved(reportSolvingProblems).map {
                  reports => ClearingReportsResult (reportSolvingProblems, reports)
                }
    } yield  result
  }

  def saveReport(report: Report): Future[Unit] = {
    def ifReportSolvesProblems(f: => Future[Unit]): Future[Unit] =
      if (report.inspectionResults.isEmpty) f else Future.successful(())

    for {
      _ <- reportsRepository.saveReport(report)
      _ <- ifReportSolvesProblems(markPreviousReportsAsResolved(report).map(_ => ()))
    } yield ()
  }

  def saveLeaks(report: Report):Future[Unit] = {
    val leaks = report.inspectionResults.map(r => Leak(
      repoName    = report.repoName,
      branch      = report.branch,
      timestamp   = report.timestamp,
      reportId    = report.id,
      ruleId      = r.ruleId.getOrElse("Unknown"),
      description = r.description,
      filePath    = r.filePath,
      scope       = r.scope,
      lineNumber  = r.lineNumber,
      urlToSource = r.urlToSource,
      lineText    = r.lineText,
      matches     = r.matches,
      priority    = r.priority,
      isTruncated = r.isTruncated.getOrElse(false))
    )
    // todo: make use to the stats returned by the update, maybe send to timeline when its done?
    leakRepository.update(report.repoName, report.branch, leaks).map(_ => ())
  }

  private def markPreviousReportsAsResolved(report: Report): Future[List[Report]] =
    for {
      unresolvedReports <- reportsRepository.findUnresolvedWithProblems(Repository(report.repoName), Some(Branch(report.branch))).map(_.toList)
      resolvedReports   =  unresolvedReports.map { unresolvedReport =>
                             val leakResolution =
                               LeakResolution(
                                 timestamp = report.timestamp,
                                 commitId  = report.commitId,
                                 resolvedLeaks = unresolvedReport.inspectionResults.map { reportLine =>
                                   ResolvedLeak(
                                     ruleId      = reportLine.ruleId.getOrElse(""),
                                     description = reportLine.description
                                   )
                                 }
                               )
                             unresolvedReport.copy(leakResolution = Some(leakResolution), inspectionResults = Nil)
                           }
        _               <- traverseFuturesSequentially(resolvedReports)(reportsRepository.updateReport)
    } yield unresolvedReports

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    for {
      total      <- reportsRepository.countAll()
      unresolved <- reportsRepository.howManyUnresolved()
      resolved   <- reportsRepository.howManyResolved()
      byRepo     <- reportsRepository.howManyUnresolvedByRepository()
      teams      <- teamsAndRepositoriesConnector.teamsWithRepositories()
    } yield {

      def ownedRepos(team: Team): Seq[String] =
        team.repos.fold(Seq.empty[String])(_.values.toSeq.flatten)
          .filterNot(repositoriesToIgnore.contains)

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
