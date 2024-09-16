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

import org.mockito.Mockito.when
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.stubbing.OngoingStubbing
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config.*
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model.*
import uk.gov.hmrc.leakdetection.scanner.Match

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SummaryServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures:

  val leaksService: LeaksService =
    mock[LeaksService]

  val warningsService: WarningsService =
    mock[WarningsService]

  val activeBranchesService: ActiveBranchesService =
    mock[ActiveBranchesService]

  lazy val teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector =
    mock[TeamsAndRepositoriesConnector]

  lazy val ruleService: RuleService =
    mock[RuleService]

  lazy val ignoreListConfig: IgnoreListConfig =
    mock[IgnoreListConfig]

  val service: SummaryService =
    SummaryService(ruleService, leaksService, warningsService, activeBranchesService, teamsAndRepositoriesConnector)

  def givenSomeLeaks(timestamp: Instant): OngoingStubbing[Future[Seq[Leak]]] =
    when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(
      Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
        aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)),
        aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp, isExcluded = true),
      )))

  def givenSomeWarnings(timestamp: Instant): OngoingStubbing[Future[Seq[Warning]]] =
    when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(
      Seq(aWarning.copy(repoName = "repo1", branch = "other", timestamp = timestamp),
        aWarning.copy(repoName = "repo1", branch = "other", timestamp = timestamp),
        aWarning.copy(repoName = "repo2", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
        aWarning.copy(repoName = "repo3", timestamp = timestamp),
        aWarning.copy(repoName = "repo3", branch = "branch1", timestamp = timestamp.minus(1, HOURS))
      )))

  def givenSomeActiveBranches(timestamp: Instant): OngoingStubbing[Future[Seq[ActiveBranch]]] =
    when(activeBranchesService.getActiveBranches(any)).thenReturn(Future.successful(
      Seq(anActiveBranch.copy(repoName = "repo1", branch = "branch", created = timestamp, updated = timestamp),
        anActiveBranch.copy(repoName = "repo1", branch = "other", created = timestamp, updated = timestamp),
        anActiveBranch.copy(repoName = "repo1", branch = "noIssues", created = timestamp, updated = timestamp),
        anActiveBranch.copy(repoName = "repo2", branch = "branch1", created = timestamp.minus(3, HOURS), updated = timestamp.minus(3, HOURS)),
        anActiveBranch.copy(repoName = "repo2", branch = "branch2", created = timestamp.minus(1, HOURS), updated = timestamp.minus(1, HOURS)),
        anActiveBranch.copy(repoName = "repo3", branch = "branch", created = timestamp, updated = timestamp),
        anActiveBranch.copy(repoName = "repo3", branch = "branch1", created = timestamp.minus(1, HOURS), updated = timestamp.minus(1, HOURS))
      )))

  "summary service" should:
    val timestamp: Instant =
      Instant.now.minus(2, HOURS)

    "generate rule summaries by rule, repository and branch" should:
      val repositoryInfo: TeamsAndRepositoriesConnector.RepositoryInfo =
        TeamsAndRepositoriesConnector.RepositoryInfo(name = "repo1", isPrivate = true, isArchived = true, defaultBranch = "main")
        
      when(teamsAndRepositoriesConnector.archivedRepos()).thenReturn(Future(Seq(repositoryInfo)))

      when(ruleService.getAllRules).thenReturn(Seq(
        aRule.copy(id = "rule-1"),
        aRule.copy(id = "rule-2"),
        aRule.copy(id = "rule-3")
      ))
      
      "include all leaks when no filters applied" in:
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results: Seq[Summary] =
          service.getRuleSummaries(None, None, None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary(
              repository      = "repo2",
              isArchived      = false,
              firstScannedAt  = timestamp.minus(3, HOURS),
              lastScannedAt   = timestamp.minus(1, HOURS),
              warningCount    = 1,
              unresolvedCount = 2,
              excludedCount   = 0,
              branchSummary   = None),
            RepositorySummary(
              repository      = "repo1",
              isArchived      = true,
              firstScannedAt  = timestamp,
              lastScannedAt   = timestamp,
              warningCount    = 2,
              unresolvedCount = 1,
              excludedCount   = 1,
              branchSummary   = None)
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary(
              repository      = "repo1",
              isArchived      = true,
              firstScannedAt  = timestamp,
              lastScannedAt   = timestamp,
              warningCount    = 2,
              unresolvedCount = 1,
              excludedCount   = 0,
              branchSummary   = None)
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )

      "only include leaks associated the team if teamName is provided" in:
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        when(teamsAndRepositoriesConnector.reposWithTeams(eqTo("team1")))
          .thenReturn(Future.successful(Seq(repositoryInfo)))

        when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

        val results: Seq[Summary] =
          service.getRuleSummaries(None, None, Some("team1")).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary(
              repository      = "repo1",
              isArchived      = true,
              firstScannedAt  = timestamp,
              lastScannedAt   = timestamp,
              warningCount    = 2,
              unresolvedCount = 1,
              excludedCount   = 1,
              branchSummary   = None)
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary(
              repository      = "repo1",
              isArchived      = true,
              firstScannedAt  = timestamp,
              lastScannedAt   = timestamp,
              warningCount    = 2,
              unresolvedCount = 1,
              excludedCount   = 0,
              branchSummary   = None)
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
          )

    "generate repository summaries by repository, branch and rule" should:

      val repositoryInfo: TeamsAndRepositoriesConnector.RepositoryInfo =
        TeamsAndRepositoriesConnector.RepositoryInfo(name = "repo1", isPrivate = true, isArchived = true, defaultBranch = "main")
        
      when(teamsAndRepositoriesConnector.archivedRepos()).thenReturn(Future(Seq(repositoryInfo)))

      "include details when just leaks exist" in:
        when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(Seq.empty))
        when(activeBranchesService.getActiveBranches(any)).thenReturn(Future.successful(Seq.empty))
        givenSomeLeaks(timestamp)

        val results: Seq[RepositorySummary] =
          service.getRepositorySummaries(None, None, None, excludeNonIssues = false, includeBranches = false).futureValue

        results shouldBe Seq(
          RepositorySummary(
            repository      = "repo1",
            isArchived      = true,
            firstScannedAt  = timestamp,
            lastScannedAt   = timestamp,
            warningCount    = 0,
            unresolvedCount = 2,
            excludedCount   = 1,
            branchSummary   = None),
          RepositorySummary(
            repository      = "repo2",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(3, HOURS),
            lastScannedAt   = timestamp.minus(1, HOURS),
            warningCount    = 0,
            unresolvedCount = 2,
            excludedCount   = 0,
            branchSummary   = None)
        )

      "include details when just warnings exist" in:
        when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(Seq.empty))
        when(activeBranchesService.getActiveBranches(any)).thenReturn(Future.successful(Seq.empty))
        givenSomeWarnings(timestamp)

        val results: Seq[RepositorySummary] =
          service.getRepositorySummaries(None, None, None, excludeNonIssues = false, includeBranches = false).futureValue

        results shouldBe Seq(
          RepositorySummary(
            repository      = "repo1",
            isArchived      = true,
            firstScannedAt  = timestamp,
            lastScannedAt   = timestamp,
            warningCount    = 2,
            unresolvedCount = 0,
            excludedCount   = 0,
            branchSummary   = None),
          RepositorySummary(
            repository      = "repo2",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(3, HOURS),
            lastScannedAt   = timestamp.minus(3, HOURS),
            warningCount    = 1,
            unresolvedCount = 0,
            excludedCount   = 0,
            branchSummary   = None),
          RepositorySummary(
            repository      = "repo3",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(1, HOURS),
            lastScannedAt   = timestamp,
            warningCount    = 2,
            unresolvedCount = 0,
            excludedCount   = 0,
            branchSummary   = None)
        )

      "include all details when both leaks and warnings exist" in:
        when(activeBranchesService.getActiveBranches(any)).thenReturn(Future.successful(Seq.empty))
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results: Seq[RepositorySummary] =
          service.getRepositorySummaries(None, None, None, excludeNonIssues = false, includeBranches = false).futureValue

        results shouldBe Seq(
          RepositorySummary(
            repository      = "repo1",
            isArchived      = true,
            firstScannedAt  = timestamp,
            lastScannedAt   = timestamp,
            warningCount    = 2,
            unresolvedCount = 2,
            excludedCount   = 1,
            branchSummary   = None),
          RepositorySummary(
            repository      = "repo2",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(3, HOURS),
            lastScannedAt   = timestamp.minus(1, HOURS),
            warningCount    = 1,
            unresolvedCount = 2,
            excludedCount   = 0,
            branchSummary   = None),
          RepositorySummary(
            repository      = "repo3",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(1, HOURS),
            lastScannedAt   = timestamp,
            warningCount    = 2,
            unresolvedCount = 0,
            excludedCount   = 0,
            branchSummary   = None)
        )

      "include all repos with active branches when excludeNonIssues is false" in:
        when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(Seq.empty))
        when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(Seq.empty))
        givenSomeActiveBranches(timestamp)

        val results: Seq[RepositorySummary] =
          service.getRepositorySummaries(None, None, None, excludeNonIssues = false, includeBranches = false).futureValue

        results.map(_.repository).distinct should contain theSameElementsAs
          Seq("repo1", "repo2", "repo3")

      "only include details associated to the team if teamName is provided" in:
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)
        givenSomeActiveBranches(timestamp)

        when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

        val results: Seq[RepositorySummary] =
          service.getRepositorySummaries(None, None, Some("team1"), excludeNonIssues = false, includeBranches = false).futureValue

        results.map(_.repository) shouldBe Seq("repo1")

      "include branch summaries when includeBranches is true" in:
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)
        givenSomeActiveBranches(timestamp)

        val results: Seq[RepositorySummary] =
          service.getRepositorySummaries(None, None, None, excludeNonIssues = false, includeBranches = true).futureValue

        results shouldBe Seq(
          RepositorySummary(
            repository      = "repo1",
            isArchived      = true,
            firstScannedAt  = timestamp,
            lastScannedAt   = timestamp,
            warningCount    = 2,
            unresolvedCount = 2,
            excludedCount   = 1,
            branchSummary   = Some(Seq(
              BranchSummary("noIssues", "reportId", timestamp, 0, 0, 0),
              BranchSummary("branch", "reportId", timestamp, 0, 2, 1),
              BranchSummary("other", "reportId", timestamp, 2, 0, 0),
          ))),
          RepositorySummary(
            repository      = "repo2",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(3, HOURS),
            lastScannedAt   = timestamp.minus(1, HOURS),
            warningCount    = 1,
            unresolvedCount = 2,
            excludedCount   = 0,
            branchSummary   = Some(Seq(
              BranchSummary("branch1", "reportId", timestamp.minus(3, HOURS), 1, 1, 0),
              BranchSummary("branch2", "reportId", timestamp.minus(1, HOURS), 0, 1, 0)
          ))),
          RepositorySummary(
            repository      = "repo3",
            isArchived      = false,
            firstScannedAt  = timestamp.minus(1, HOURS),
            lastScannedAt   = timestamp,
            warningCount    = 2,
            unresolvedCount = 0,
            excludedCount   = 0,
            branchSummary   = Some(Seq(
              BranchSummary("branch", "reportId", timestamp, 1, 0, 0),
              BranchSummary("branch1", "reportId", timestamp.minus(1, HOURS), 1, 0, 0)
          )))
        )

  def aLeak: Leak =
    Leak(
      "repoName",
      "branch",
      Instant.now(),
      ReportId("reportId"),
      "ruleId", "description",
      "/file/path",
      Scope.FILE_CONTENT,
      1,
      "url",
      "abc = 123",
      List(Match(3, 7)),
      "high",
      false,
      None
    )

  def aRule: Rule =
    Rule(
      id = "rule",
      scope = Scope.FILE_CONTENT,
      regex = "regex",
      description = "description"
    )

  def aWarning: Warning =
    Warning(
      "repoName",
      "branch",
      Instant.now(),
      ReportId("reportId"),
      "message"
    )

  def anActiveBranch: ActiveBranch =
    ActiveBranch("repoName", "branch", "reportId", Instant.now(), Instant.now())

  when(ruleService.getAllRules).thenReturn(Seq(
    aRule.copy(id = "rule-1"),
    aRule.copy(id = "rule-2"),
    aRule.copy(id = "rule-3")
  ))
