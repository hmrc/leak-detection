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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository

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

  def getLatestReport(repository: Repository, branch: Branch): Future[Option[Report]] =
    reportsRepository
      .findLatestReport(repository, branch)

  def getRepositories: Future[Seq[String]] = reportsRepository.getDistinctRepoNames

  def getLatestReportsForEachBranch(repository: Repository): Future[List[Report]] =
    reportsRepository
      .findUnresolvedWithProblems(repository)
      .map(_.groupBy(_.branch).map {
        case (_, reports) => reports.maxBy(_.timestamp)
      }.toList)

  def getLatestReportForDefaultBranch(repository: Repository)(implicit hc: HeaderCarrier): Future[Option[Report]] =
    githubService.getDefaultBranchName(repository) flatMap { defaultBranchName =>
      reportsRepository
        .findUnresolvedWithProblems(repository, Some(defaultBranchName)).map(_.headOption)
    }

  def getReport(reportId: ReportId): Future[Option[Report]] =
    reportsRepository.findByReportId(reportId)

  def clearReportsAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[Report] = {
    val reportSolvingProblems = Report.create(
      repositoryName =  deleteBranchEvent.repositoryName,
      repositoryUrl  =  deleteBranchEvent.repositoryUrl,
      commitId       = "n/a (branch was deleted)",
      authorName     =  deleteBranchEvent.authorName,
      branch         =  deleteBranchEvent.branchRef,
      results        = Nil,
    )
    for {
      _  <- reportsRepository.saveReport(reportSolvingProblems)
    } yield  reportSolvingProblems
  }

  def saveReport(report: Report): Future[Unit] =
    reportsRepository.saveReport(report)

}

object ReportsService {
  final case class ClearingReportsResult(
    reportSolvingProblems: Report,
    previousProblems: List[Report]
  )
}
