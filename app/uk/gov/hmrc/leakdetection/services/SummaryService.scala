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
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class SummaryService @Inject()(ruleService: RuleService,
                               leaksService: LeaksService,
                               warningsService: WarningsService,
                               activeBranchesService: ActiveBranchesService,
                               teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector)
                              (implicit ec: ExecutionContext) {

  def getRuleSummaries(ruleId: Option[String], repoName: Option[String], teamName: Option[String]): Future[Seq[Summary]] =
    for {
      leaks         <- leaksService.getLeaks(repoName, None, ruleId)
      warnings      <- warningsService.getWarnings(repoName, None)
      teamRepos     <- getTeamRepos(teamName)
      filteredLeaks = filterLeaksByTeam(leaks, teamRepos)
      rules         = ruleService.getAllRules()
      leaksByRule   = groupLeaksByRule(filteredLeaks, warnings)
    } yield {
      rules.map(rule => Summary(rule, leaksByRule.getOrElse(rule.id, Seq())))
    }

  def getRepositorySummaries(ruleId: Option[String], repoName: Option[String], teamName: Option[String]): Future[Seq[RepositorySummary]] =
    for {
      activeBranches   <- repoName.map(r => activeBranchesService.getActiveBranchesForRepo(r)).getOrElse(Future.successful(Seq.empty))
      leaks            <- leaksService.getLeaks(repoName, None, ruleId)
      warnings         <- warningsService.getWarnings(repoName, None)
      teamRepos        <- getTeamRepos(teamName)
      filteredLeaks    = filterLeaksByTeam(leaks, teamRepos)
      filteredWarnings = filterWarningsByTeam(warnings, teamRepos)
      allRepositories = filteredLeaks.map(_.repoName) ++ filteredWarnings.map(_.repoName) ++ repoName
    } yield {
      val repositoryDetails =
        allRepositories
          .distinct
          .map(r =>
            (r, filteredLeaks.filter(l => l.repoName == r), filteredWarnings.filter(w => w.repoName == r))
          )

      repositoryDetails.map {
        case (repoName, repoLeaks, repoWarnings) =>
          RepositorySummary(
            repoName,
            getFirstScanned(repoLeaks, repoWarnings, activeBranches),
            getLastScanned(repoLeaks, repoWarnings, activeBranches),
            repoWarnings.length,
            getUnresolvedLeakCount(repoLeaks),
            getExcludedLeakCount(repoLeaks),
            buildBranchSummaries(activeBranches, repoLeaks, repoWarnings)
          )
      }
    }

  def buildBranchSummaries(activeBranches: Seq[ActiveBranch], leaks: Seq[Leak], warnings: Seq[Warning]): Seq[BranchSummary] = {
    val allBranches = leaks.map(_.branch) ++ warnings.map(_.branch)
    val branchDetails =
      allBranches.distinct.map(r => (r, leaks.filter(l => l.branch == r), warnings.filter(w => w.branch == r)))

    activeBranches
      .filterNot(b => branchDetails.map(_._1).contains(b.branch))
      .map(activeBranch =>
      BranchSummary(activeBranch.branch, activeBranch.reportId, activeBranch.updated, 0, 0, 0)
    ) ++
    branchDetails.map {
      case (branch, branchLeaks, branchWarnings) =>
        BranchSummary(
          branch,
          branchLeaks.headOption.map(_.reportId).getOrElse(branchWarnings.head.reportId).value,
          branchLeaks.headOption.map(_.timestamp).getOrElse(branchWarnings.head.timestamp),
          branchWarnings.length,
          getUnresolvedLeakCount(branchLeaks),
          getExcludedLeakCount(branchLeaks)
        )
    }
  }

  private def getUnresolvedLeakCount(leaks: Seq[Leak]): Int = leaks.filterNot(_.isExcluded).length

  private def getExcludedLeakCount(leaks: Seq[Leak]): Int   = leaks.filter(_.isExcluded).length

  private def getFirstScanned(leaks: Seq[Leak], warnings: Seq[Warning], activeBranches: Seq[ActiveBranch]): Instant =
    (leaks, warnings, activeBranches) match {
      case (l, _, _) if l.nonEmpty => l.minBy(_.timestamp).timestamp
      case (_, w, _) if w.nonEmpty => w.minBy(_.timestamp).timestamp
      case (_, _, a) => a.minBy(_.updated).updated
    }

  private def getLastScanned(leaks: Seq[Leak], warnings: Seq[Warning], activeBranches: Seq[ActiveBranch]): Instant =
    (leaks, warnings, activeBranches) match {
      case (l, _, _) if l.nonEmpty => l.maxBy(_.timestamp).timestamp
      case (_, w, _) if w.nonEmpty => w.maxBy(_.timestamp).timestamp
      case (_, _, a) => a.maxBy(_.updated).updated
    }

  private def getTeamRepos(teamName: Option[String]): Future[Option[Seq[String]]] = teamName match {
    case Some(t) => teamsAndRepositoriesConnector.team(t).map(_.map(_.repos.map(_.values.toSeq.flatten).toSeq.flatten))
    case None    => Future.successful(None)
  }

  private def filterLeaksByTeam(leaks: Seq[Leak], repos: Option[Seq[String]]): Seq[Leak] = repos match {
    case Some(r) => leaks.filter(l => r.contains(l.repoName))
    case None    => leaks
  }

  private def filterWarningsByTeam(warnings: Seq[Warning], repos: Option[Seq[String]]): Seq[Warning] = repos match {
    case Some(r) => warnings.filter(l => r.contains(l.repoName))
    case None    => warnings
  }

  private def groupLeaksByRule(leaks: Seq[Leak], warnings: Seq[Warning]): Map[String, Seq[RepositorySummary]] = leaks
    .groupBy(_.ruleId)
    .map {
      case (ruleId, leaksByRule) =>
        (
          ruleId,
          leaksByRule
            .groupBy(_.repoName)
            .map {
              case (repoName, ruleLeaksByRepo) =>
                RepositorySummary(
                  repoName,
                  ruleLeaksByRepo.minBy(_.timestamp).timestamp,
                  ruleLeaksByRepo.maxBy(_.timestamp).timestamp,
                  warnings.count(_.repoName == repoName),
                  getUnresolvedLeakCount(ruleLeaksByRepo),
                  getExcludedLeakCount(ruleLeaksByRepo),
                  ruleLeaksByRepo
                    .groupBy(_.branch)
                    .map {
                      case (branch, leaksByBranch) =>
                        BranchSummary(
                          branch,
                          leaksByBranch.head.reportId.value,
                          leaksByBranch.head.timestamp,
                          warnings.count(w => w.repoName == repoName && w.branch == branch),
                          getUnresolvedLeakCount(leaksByBranch),
                          getExcludedLeakCount(leaksByBranch)
                        )
                    }
                    .toSeq
                )
            }
            .toSeq)
    }
}
