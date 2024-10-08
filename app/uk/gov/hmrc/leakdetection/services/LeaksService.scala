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
import uk.gov.hmrc.leakdetection.config.IgnoreListConfig
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ExecutionContext, Future}

class LeaksService @Inject()(
  leakRepository               : LeakRepository,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  ignoreListConfig             : IgnoreListConfig
)(using ExecutionContext
) extends MetricSource:

  def getRepositoriesWithUnresolvedLeaks: Future[Seq[String]] =
    leakRepository.findDistinctRepoNamesWithUnresolvedLeaks()

  def getLeaks(repoName: Option[String], branch: Option[String], ruleId: Option[String]): Future[Seq[Leak]] =
    leakRepository.findLeaksBy(ruleId = ruleId, repoName = repoName, branch = branch)

  def getLeaksForReport(reportId: ReportId): Future[Seq[Leak]] =
    leakRepository.findLeaksForReport(reportId.value)

  def clearBranchLeaks(repoName: String, branchName: String): Future[Long] =
    leakRepository.removeBranch(repoName, branchName)

  def clearRepoLeaks(repoName:String): Future[Long] =
    leakRepository.removeRepository(repoName)

  def saveLeaks(repo: Repository, branch: Branch, leaks: Seq[Leak]): Future[Unit] =
    leakRepository.update(repo.asString, branch.asString, leaks).map(_ => ())

  override def metrics(using ExecutionContext): Future[Map[String, Int]] =
    for
      total      <- leakRepository.countAll()
      unresolved <- leakRepository.countAll()
      byRepo     <- leakRepository.countByRepo()
      teams      <- teamsAndRepositoriesConnector.teams()
    yield
      val byTeamStats =
        teams
          .map: t =>
            s"reports.teams.${t.normalisedName}.unresolved" -> t.repos
              .filterNot(ignoreListConfig.repositoriesToIgnore.contains)
              .map(r => byRepo.getOrElse(r, 0))
              .sum
          .toMap

      val globalStats: Map[String, Int] =
        Map(
          "reports.total"      -> total,
          "reports.unresolved" -> unresolved
        )

      globalStats ++ byTeamStats
