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
      filteredLeaks = teamRepos.foldLeft(leaks)((acc, l) => acc.filter(a => l.contains(a.repoName)))
      rules         = ruleService.getAllRules()
      leaksByRule   = groupLeaksByRule(filteredLeaks, warnings)
    } yield {
      rules.map(rule => Summary(rule, leaksByRule.getOrElse(rule.id, Seq())))
    }

  def getRepositorySummaries(ruleId: Option[String], repoName: Option[String], teamName: Option[String], excludeNonIssues: Boolean, includeBranches: Boolean): Future[Seq[RepositorySummary]] =
    for {
      activeBranches  <- if(excludeNonIssues) Future.successful(Seq.empty) else activeBranchesService.getActiveBranches(repoName)
      leaks           <- leaksService.getLeaks(repoName, None, ruleId)
      warnings        <- warningsService.getWarnings(repoName, None)
      teamRepos       <- getTeamRepos(teamName)
      filteredBranches = teamRepos.foldLeft(activeBranches)((acc, r) => acc.filter(a => r.contains(a.repoName)))
      filteredLeaks    = teamRepos.foldLeft(leaks)((acc, l) => acc.filter(a => l.contains(a.repoName)))
      filteredWarnings = teamRepos.foldLeft(warnings)((acc, w) => acc.filter(a => w.contains(a.repoName)))
      allRepositories  = filteredLeaks.map(_.repoName) ++ filteredWarnings.map(_.repoName) ++ filteredBranches.map(_.repoName)
    } yield {
      val repositoryDetails =
        allRepositories
          .distinct
          .map(r => (
            r,
            filteredLeaks.filter(l => l.repoName == r),
            filteredWarnings.filter(w => w.repoName == r),
            filteredBranches.filter(a => a.repoName == r)
          ))

      repositoryDetails.map {
        case (repoName, repoLeaks, repoWarnings, repoActiveBranches) =>
          RepositorySummary(
            repoName,
            (repoLeaks.map(_.timestamp) ++ repoWarnings.map(_.timestamp) ++ repoActiveBranches.map(_.created)).min,
            (repoLeaks.map(_.timestamp) ++ repoWarnings.map(_.timestamp) ++ repoActiveBranches.map(_.updated)).max,
            repoWarnings.length,
            getUnresolvedLeakCount(repoLeaks),
            getExcludedLeakCount(repoLeaks),
            if(includeBranches) Some(buildBranchSummaries(repoActiveBranches, repoLeaks, repoWarnings)) else None
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

  private def getTeamRepos(teamName: Option[String]): Future[Option[Seq[String]]] = teamName match {
    case Some(t) => teamsAndRepositoriesConnector.team(t).map(_.map(_.repos.map(_.values.toSeq.flatten).toSeq.flatten))
    case None    => Future.successful(None)
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
                  branchSummary = None
                )
            }
            .toSeq)
    }
}
