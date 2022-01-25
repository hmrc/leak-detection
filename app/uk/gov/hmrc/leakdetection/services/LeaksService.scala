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
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, IgnoreListConfig}

import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ExecutionContext, Future}

class LeaksService @Inject()(ruleService: RuleService,
                             leakRepository: LeakRepository,
                             teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
                             ignoreListConfig: IgnoreListConfig)
                            (implicit ec: ExecutionContext) extends MetricSource {

  def getRepositories: Future[Seq[String]] = leakRepository.findDistinctRepoNames()

  def getLeaks(repoName: Option[String], branch: Option[String], ruleId: Option[String]): Future[Seq[Leak]] =
    leakRepository.findLeaksBy(ruleId = ruleId, repoName = repoName, branch = branch)

  def getSummaries(ruleId: Option[String], repoName: Option[String], teamName: Option[String]): Future[Seq[Summary]] = {
    for {
      leaks         <- leakRepository.findLeaksBy(ruleId = ruleId, repoName = repoName)
      teamRepos     <- getTeamRepos(teamName)
      filteredLeaks  = filterLeaksByTeam(leaks, teamRepos)
      rules          = ruleService.getAllRules()
    } yield {
      val leaksByRule: Map[String, Seq[RepositorySummary]] = filteredLeaks.groupBy(_.ruleId)
        .map { case (ruleId, leaksByRule) =>
          (ruleId, leaksByRule.groupBy(_.repoName)
            .map { case (repoName, ruleLeaksByRepo) => RepositorySummary(
              repoName,
              ruleLeaksByRepo.minBy(_.timestamp).timestamp,
              ruleLeaksByRepo.maxBy(_.timestamp).timestamp,
              ruleLeaksByRepo.length,
              ruleLeaksByRepo.groupBy(_.branch)
                .map { case (branch, leaksByBranch) => BranchSummary(
                  branch, leaksByBranch.head.reportId, leaksByBranch.head.timestamp, leaksByBranch.length)
                }.toSeq)
            }.toSeq)
        }

      rules.map(rule => Summary(rule, leaksByRule.getOrElse(rule.id, Seq()))
      )
    }
  }

  def getLeaksForReport(reportId: ReportId): Future[Seq[Leak]] = {
    leakRepository.findLeaksForReport(reportId.value)
  }

  private def getTeamRepos(teamName: Option[String]): Future[Option[Seq[String]]] = teamName match {
    case Some(t) => teamsAndRepositoriesConnector.team(t).map(_.map(_.repos.map(_.values.toSeq.flatten).toSeq.flatten))
    case None    => Future.successful(None)
  }

  private def filterLeaksByTeam(leaks: Seq[Leak], repos: Option[Seq[String]]): Seq[Leak] = repos match {
    case Some(r) => leaks.filter(l => r.contains(l.repoName))
    case None    => leaks
  }

  def clearLeaksAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[Long] =
    leakRepository.removeBranch(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)

  def saveLeaks(repo:Repository, branch:Branch, leaks: Seq[Leak]):Future[Unit] =
    leakRepository.update(repo.asString, branch.asString, leaks).map(_ => ())

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    for {
      total      <- leakRepository.countAll()
      unresolved <- leakRepository.countAll()
      byRepo     <- leakRepository.countByRepo()
      teams      <- teamsAndRepositoriesConnector.teamsWithRepositories()
    } yield {

      def ownedRepos(team: Team): Seq[String] =
        team.repos.fold(Seq.empty[String])(_.values.toSeq.flatten)
          .filterNot(ignoreListConfig.repositoriesToIgnore.contains)

      val byTeamStats = teams
        .map(t => s"reports.teams.${t.normalisedName}.unresolved" -> ownedRepos(t).map(r => byRepo.getOrElse(r, 0)).sum)
        .toMap

      val globalStats = Map(
        "reports.total" -> total,
        "reports.unresolved" -> unresolved
      )

      globalStats ++ byTeamStats
    }
}
