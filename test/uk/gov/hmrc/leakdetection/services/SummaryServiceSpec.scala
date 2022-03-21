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

import org.mockito.ArgumentMatchers.{eq => mockEq}
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.scanner.Match

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SummaryServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures {

  val leaksService = mock[LeaksService]
  val warningsService = mock[WarningsService]
  val activeBranchesService = mock[ActiveBranchesService]

  lazy val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  lazy val ruleService = mock[RuleService]
  lazy val ignoreListConfig = mock[IgnoreListConfig]

  val service = new SummaryService(ruleService, leaksService, warningsService, activeBranchesService, teamsAndRepositoriesConnector)

  def givenSomeLeaks(timestamp: Instant) = when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(
    Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
      aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
      aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
      aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)),
      aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp, isExcluded = true),
    )))

  def givenSomeWarnings(timestamp: Instant) = when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(
    Seq(aWarning.copy(repoName = "repo1", branch = "other", timestamp = timestamp),
      aWarning.copy(repoName = "repo1", branch = "other", timestamp = timestamp),
      aWarning.copy(repoName = "repo2", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
      aWarning.copy(repoName = "repo3", timestamp = timestamp),
      aWarning.copy(repoName = "repo3", branch = "branch1", timestamp = timestamp.minus(1, HOURS))
    )))

  def givenSomeActiveBranches(timestamp: Instant) = when(activeBranchesService.getAllActiveBranches()).thenReturn(Future.successful(
    Seq(anActiveBranch.copy(repoName = "repo1", branch = "branch", created = timestamp, updated = timestamp),
      anActiveBranch.copy(repoName = "repo1", branch = "other", created = timestamp, updated = timestamp),
      anActiveBranch.copy(repoName = "repo1", branch = "noIssues", created = timestamp, updated = timestamp),
      anActiveBranch.copy(repoName = "repo2", branch = "branch1", created = timestamp, updated = timestamp),
      anActiveBranch.copy(repoName = "repo3", branch = "branch", created = timestamp, updated = timestamp),
      anActiveBranch.copy(repoName = "repo3", branch = "branch1", created = timestamp, updated = timestamp)
    )))

  def givenSomeActiveBranches(repoName: String) = when(activeBranchesService.getActiveBranchesForRepo(repoName)).thenReturn(Future.successful(
    Seq(anActiveBranch.copy(repoName = repoName, branch = "branch"),
      anActiveBranch.copy(repoName = repoName, branch = "noIssues")
    )))

  "summary service" should {
    val timestamp = Instant.now.minus(2, HOURS)

    "generate rule summaries by rule, repository and branch" should {
      when(ruleService.getAllRules()).thenReturn(Seq(
        aRule.copy(id = "rule-1"),
        aRule.copy(id = "rule-2"),
        aRule.copy(id = "rule-3")
      ))
      "include all leaks when no filters applied" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results = service.getRuleSummaries(None, None, None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 2, 1, 1, None),
            RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 1, 2, 0, None)
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 2, 1, 0, None)
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the team if teamName is provided" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        when(teamsAndRepositoriesConnector.team(mockEq("team1")))
          .thenReturn(Future.successful(Option(Team("team1", None, None, None, Some(Map("Service" -> Seq("repo1")))))))

        when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

        val results = service.getRuleSummaries(None, None, Some("team1")).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 2, 1, 1, None)
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 2, 1, 0, None)
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
          )
      }
    }

    "generate repository summaries by repository, branch and rule" should {
      "include details when just leaks exist" in {
        when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(Seq.empty))
        when(activeBranchesService.getAllActiveBranches()).thenReturn(Future.successful(Seq.empty))
        givenSomeLeaks(timestamp)

        val results = service.getRepositorySummaries(None, None, None, false).futureValue

        results shouldBe Seq(
          RepositorySummary("repo1", timestamp, timestamp, 0, 2, 1, None),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 0, 2, 0, None)
        )
      }

      "include details when just warnings exist" in {
        when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(Seq.empty))
        when(activeBranchesService.getAllActiveBranches()).thenReturn(Future.successful(Seq.empty))
        givenSomeWarnings(timestamp)

        val results = service.getRepositorySummaries(None, None, None, false).futureValue

        results shouldBe Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 0, 0, None),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(3, HOURS), 1, 0, 0, None),
          RepositorySummary("repo3", timestamp.minus(1, HOURS), timestamp, 2, 0, 0, None)
        )
      }

      "include all details when both leaks and warnings exist" in {
        when(activeBranchesService.getAllActiveBranches()).thenReturn(Future.successful(Seq.empty))
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results = service.getRepositorySummaries(None, None, None, false).futureValue

        results shouldBe Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 2, 1, None),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 1, 2, 0, None),
          RepositorySummary("repo3", timestamp.minus(1, HOURS), timestamp, 2, 0, 0, None)
        )
      }

      "include all repos with active branches when repoName not provided" in {
        when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(Seq.empty))
        when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(Seq.empty))
        givenSomeActiveBranches(timestamp)

        val results = service.getRepositorySummaries(None, None, None, false).futureValue

        results.map(_.repository).distinct should contain theSameElementsAs
          Seq("repo1", "repo2", "repo3")
      }

      "only include details associated to the team if teamName is provided" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)
        givenSomeActiveBranches(timestamp)

        when(teamsAndRepositoriesConnector.team(mockEq("team1")))
          .thenReturn(Future.successful(Option(Team("team1", None, None, None, Some(Map("Service" -> Seq("repo1")))))))

        when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

        val results = service.getRepositorySummaries(None, None, Some("team1"), false).futureValue

        results.map(_.repository) shouldBe Seq("repo1")
      }

      "include branch summaries" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)
        givenSomeActiveBranches(timestamp)

        val results = service.getRepositorySummaries(None, None, None, true).futureValue

        results shouldBe Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 2, 1, Some(Seq(
            BranchSummary("noIssues", "reportId", timestamp, 0, 0, 0),
            BranchSummary("branch", "reportId", timestamp, 0, 2, 1),
            BranchSummary("other", "reportId", timestamp, 2, 0, 0),
          ))),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 1, 2, 0, Some(Seq(
            BranchSummary("branch1", "reportId", timestamp.minus(3, HOURS), 1, 1, 0),
            BranchSummary("branch2", "reportId", timestamp.minus(1, HOURS), 0, 1, 0)
          ))),
          RepositorySummary("repo3", timestamp.minus(1, HOURS), timestamp, 2, 0, 0, Some(Seq(
            BranchSummary("branch", "reportId", timestamp, 1, 0, 0),
            BranchSummary("branch1", "reportId", timestamp.minus(1, HOURS), 1, 0, 0)
          )))
        )
      }

      "include branch summaries for repo when repoName provided" in {
        when(warningsService.getWarnings(any, any)).thenReturn(Future.successful(Seq.empty))
        when(leaksService.getLeaks(any, any, any)).thenReturn(Future.successful(Seq.empty))
        givenSomeActiveBranches("repo1")

        val results = service.getRepositorySummaries(None, Some("repo1"), None, true).futureValue

        results.flatMap(_.branchSummary.getOrElse(Seq.empty).map(_.branch)) shouldBe Seq("branch", "noIssues")
      }
    }
  }

  def aLeak = Leak(
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
    false
  )

  def aRule = Rule(
    id = "rule",
    scope = Scope.FILE_CONTENT,
    regex = "regex",
    description = "description"
  )

  def aWarning = Warning(
    "repoName",
    "branch",
    Instant.now(),
    ReportId("reportId"),
    "message"
  )

  def anActiveBranch = ActiveBranch("repoName", "branch", "reportId", Instant.now(), Instant.now())

  when(ruleService.getAllRules()).thenReturn(Seq(
    aRule.copy(id = "rule-1"),
    aRule.copy(id = "rule-2"),
    aRule.copy(id = "rule-3")
  ))
}
