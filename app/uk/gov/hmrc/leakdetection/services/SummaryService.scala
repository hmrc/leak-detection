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
                               teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector)
                              (implicit ec: ExecutionContext) {

  def buildBranchSummaries(leaks: Seq[Leak], warnings: Seq[Warning]): Seq[BranchSummary] = {
    val allBranches = leaks.map(_.branch) ++ warnings.map(_.branch)
    val branchDetails = allBranches.distinct.map(r => (r, leaks.filter(l => l.branch == r), warnings.filter(w => w.branch == r)))
    branchDetails.map { case (branch, branchLeaks, branchWarnings) =>
      BranchSummary(
        branch,
        branchLeaks.headOption.map(_.reportId).getOrElse(branchWarnings.head.reportId),
        branchLeaks.headOption.map(_.timestamp).getOrElse(branchWarnings.head.timestamp),
        branchWarnings.length,
        branchLeaks.length,
        branchLeaks.groupBy(_.ruleId).map(leaksByRule =>
          leaksByRule._1 -> leaksByRule._2.length)
      )
    }
  }

  def getSummaries(ruleId: Option[String], repoName: Option[String], teamName: Option[String]): Future[Summary] = {
    for {
      leaks <- leaksService.getLeaks(ruleId, repoName, None)
      warnings <- warningsService.getWarnings(repoName, None)
      teamRepos <- getTeamRepos(teamName)
      filteredLeaks = filterLeaksByTeam(leaks, teamRepos)
      filteredWarnings = filterWarningsByTeam(warnings, teamRepos)
      allRepositories = filteredLeaks.map(_.repoName) ++ filteredWarnings.map(_.repoName)
      rules = ruleService.getAllRules()
    } yield {
      val repositoryDetails = allRepositories.distinct.map(r =>
        (r, filteredLeaks.filter(l => l.repoName == r), filteredWarnings.filter(w => w.repoName == r)))
      val repositorySummaries: Seq[RepositorySummary] = repositoryDetails.map { case (repoName, repoLeaks, repoWarnings) =>
        RepositorySummary(
          repoName,
          if (repoLeaks.nonEmpty) repoLeaks.minBy(_.timestamp).timestamp else repoWarnings.minBy(_.timestamp).timestamp,
          if (repoLeaks.nonEmpty) repoLeaks.maxBy(_.timestamp).timestamp else repoWarnings.maxBy(_.timestamp).timestamp,
          repoWarnings.length,
          repoLeaks.length,
          buildBranchSummaries(repoLeaks, repoWarnings)
        )
      }

      Summary(rules, repositorySummaries)
    }
  }

  private def getTeamRepos(teamName: Option[String]): Future[Option[Seq[String]]] = teamName match {
    case Some(t) => teamsAndRepositoriesConnector.team(t).map(_.map(_.repos.map(_.values.toSeq.flatten).toSeq.flatten))
    case None => Future.successful(None)
  }

  private def filterLeaksByTeam(leaks: Seq[Leak], repos: Option[Seq[String]]): Seq[Leak] = repos match {
    case Some(r) => leaks.filter(l => r.contains(l.repoName))
    case None => leaks
  }

  private def filterWarningsByTeam(warnings: Seq[Warning], repos: Option[Seq[String]]): Seq[Warning] = repos match {
    case Some(r) => warnings.filter(l => r.contains(l.repoName))
    case None => warnings
  }
}
