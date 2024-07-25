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
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model._

import scala.concurrent.{ExecutionContext, Future}

class SummaryService @Inject()(
  ruleService                  : RuleService,
  leaksService                 : LeaksService,
  warningsService              : WarningsService,
  activeBranchesService        : ActiveBranchesService,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext) {

  def getRuleSummaries(
    ruleId  : Option[String],
    repoName: Option[String],
    teamName: Option[String]
  ): Future[Seq[Summary]] =
    for {
      leaks            <- leaksService.getLeaks(repoName, None, ruleId)
      warnings         <- warningsService.getWarnings(repoName, None)
      teamRepos        <- getTeamRepos(teamName)
      allArchivedRepos <- teamsAndRepositoriesConnector.archivedRepos().map(_.map(repo => repo.name))
      teamRepoNames    =  teamRepos.map(repo => repo.name)
      filteredLeaks    =  teamName.fold(leaks)(_ => leaks.filter(l => teamRepoNames.contains(l.repoName)))
      rules            =  ruleService.getAllRules()
      leaksByRule      =  groupLeaksByRule(allArchivedRepos.toSet, filteredLeaks, warnings)
    } yield
      rules.map(rule => Summary(rule, leaksByRule.getOrElse(rule.id, Seq())))

  def getRepositorySummaries(
    ruleId          : Option[String],
    repoName        : Option[String],
    teamName        : Option[String],
    excludeNonIssues: Boolean,
    includeBranches : Boolean
  ): Future[Seq[RepositorySummary]] =
    for {
      activeBranches   <- if (excludeNonIssues)
                            Future.successful(Seq.empty)
                          else activeBranchesService.getActiveBranches(repoName)
      leaks            <- leaksService.getLeaks(repoName, None, ruleId)
      warnings         <- warningsService.getWarnings(repoName, None)
      teamRepos        <- getTeamRepos(teamName)
      allArchivedRepos <- teamsAndRepositoriesConnector.archivedRepos().map(_.map(_.name))
      teamRepoNames    =  teamRepos.map(_.name.toLowerCase)
      filteredBranches =  teamName.fold(activeBranches)(_ => activeBranches.filter(a => teamRepoNames.contains(a.repoName)))
      filteredLeaks    =  teamName.fold(leaks         )(_ => leaks         .filter(l => teamRepoNames.contains(l.repoName)))
      filteredWarnings =  teamName.fold(warnings      )(_ => warnings      .filter(w => teamRepoNames.contains(w.repoName)))
      allRepoNames     =  filteredLeaks.map(_.repoName) ++ filteredWarnings.map(_.repoName) ++ filteredBranches.map(_.repoName)
    } yield
      allRepoNames
        .distinct
        .map { repoName =>
          val repoLeaks          = filteredLeaks   .filter(_.repoName == repoName)
          val repoWarnings       = filteredWarnings.filter(_.repoName == repoName)
          val repoActiveBranches = filteredBranches.filter(_.repoName == repoName)
          RepositorySummary(
            repository      = repoName,
            isArchived      = allArchivedRepos.toSet.contains(repoName),
            firstScannedAt  = (repoLeaks.map(_.timestamp) ++ repoWarnings.map(_.timestamp) ++ repoActiveBranches.map(_.created)).min,
            lastScannedAt   = (repoLeaks.map(_.timestamp) ++ repoWarnings.map(_.timestamp) ++ repoActiveBranches.map(_.updated)).max,
            warningCount    = repoWarnings.length,
            unresolvedCount = getUnresolvedLeakCount(repoLeaks),
            excludedCount   = getExcludedLeakCount(repoLeaks),
            branchSummary   = if (includeBranches)
                                Some(buildBranchSummaries(repoActiveBranches, repoLeaks, repoWarnings))
                              else None
          )
      }

  def buildBranchSummaries(
    activeBranches: Seq[ActiveBranch],
    leaks         : Seq[Leak],
    warnings      : Seq[Warning]
  ): Seq[BranchSummary] = {
    val allBranches = leaks.map(_.branch) ++ warnings.map(_.branch)
    val branchDetails =
      allBranches.distinct.map(r => (r, leaks.filter(_.branch == r), warnings.filter(_.branch == r)))

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

  private def getUnresolvedLeakCount(leaks: Seq[Leak]): Int =
    leaks.filterNot(_.isExcluded).length

  private def getExcludedLeakCount(leaks: Seq[Leak]): Int =
    leaks.filter(_.isExcluded).length

  private def getTeamRepos(teamName: Option[String]): Future[Seq[TeamsAndRepositoriesConnector.RepositoryInfo]] = teamName match {
    case Some(t) => teamsAndRepositoriesConnector.reposWithTeams(t)
    case None    => Future.successful(Seq())
  }

  private def groupLeaksByRule(
    allArchivedRepos: Set[String],
    leaks           : Seq[Leak],
    warnings        : Seq[Warning]
  ): Map[String, Seq[RepositorySummary]] =
    leaks
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
                    repository      = repoName,
                    isArchived      = allArchivedRepos.contains(repoName),
                    firstScannedAt  = ruleLeaksByRepo.minBy(_.timestamp).timestamp,
                    lastScannedAt   = ruleLeaksByRepo.maxBy(_.timestamp).timestamp,
                    warningCount    = warnings.count(_.repoName == repoName),
                    unresolvedCount = getUnresolvedLeakCount(ruleLeaksByRepo),
                    excludedCount   = getExcludedLeakCount(ruleLeaksByRepo),
                    branchSummary   = None
                  )
              }
              .toSeq
          )
      }
}
