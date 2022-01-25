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

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.{anyString, eq => mockEq}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Instant, LocalDateTime}
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LeaksServiceSpec extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[Leak] with MockitoSugar {

  override val repository = new LeakRepository(mongoComponent)

  lazy val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  lazy val ruleService = mock[RuleService]
  lazy val ignoreListConfig = mock[IgnoreListConfig]

  val leaksService = new LeaksService(ruleService, repository, teamsAndRepositoriesConnector, ignoreListConfig)

  "Leaks service" should {
    val timestamp = Instant.now.minus(2, HOURS)
    "generate summaries as groups of leaks by rule, repository and branch" should {
      when(ruleService.getAllRules()).thenReturn(Seq(
        aRule.copy(id = "rule-1"),
        aRule.copy(id = "rule-2"),
        aRule.copy(id = "rule-3")
      ))

      "include all leaks when no filters applied" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        val results = leaksService.getSummaries(None, None, None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            )),
            RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2, Seq(
              BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1),
              BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the rule if ruleId is provided" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        val results = leaksService.getSummaries(Some("rule-1"), None, None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            )),
            RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2, Seq(
              BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1),
              BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-2"), Seq()),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the repository if repoName is provided" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        val results = leaksService.getSummaries(None, Some("repo1"), None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the team if teamName is provided" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        when(teamsAndRepositoriesConnector.team(mockEq("team1")))
          .thenReturn(Future.successful(Option(Team("team1", None, None, None, Some(Map("Service" -> Seq("repo1")))))))

      when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

        val results = leaksService.getSummaries(None, None, Some("team1")).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(BranchSummary("branch", ReportId("reportId"), timestamp, 1))))
          ),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }
    }
    "get leaks for a report" in {
      repository.collection.insertMany(

        Seq(aLeak.copy(repoName = "repo1", reportId = ReportId("otherReport"), ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture.futureValue

      val results = leaksService.getLeaksForReport(ReportId("reportId")).futureValue

      results shouldBe Seq(
        aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS))
      )
    }
  }


  "produce a metric of the total active leaks" in {

    val leaks = few(() => aLeak)
    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)
    when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(Seq.empty))
    repository.collection.insertMany(leaks).toFuture().futureValue

    leaksService.metrics.futureValue should contain allOf (
      "reports.total"      -> leaks.length,
      "reports.unresolved" -> leaks.length
    )
  }

  "produce metrics grouped by team" in {
    val leaks = List(aLeakFor("r1", "b1"),aLeakFor("r1", "b1"), aLeakFor("r2", "b1"))
    repository.collection.insertMany(leaks).toFuture().futureValue

    val now = Some(LocalDateTime.now)
    val teams: Seq[Team] = Seq(
      Team("t1", now, now, now, Some(Map("services" -> Seq("r1")))),
      Team("t2", now, now, now, Some(Map("services" -> Seq("r2")))))
    when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(teams))
    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

    leaksService.metrics.futureValue should contain allOf (
      "reports.teams.t1.unresolved" -> 2,
      "reports.teams.t2.unresolved" -> 1
    )
  }

  "normalise team names" in {
    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)
    val now = Some(LocalDateTime.now)
    val teams: Seq[Team] = Seq(
      Team("T1", now, now, now, Some(Map("services"             -> Seq("r1")))),
      Team("T2 with spaces", now, now, now, Some(Map("services" -> Seq("r2")))))
    when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(teams))

    leaksService.metrics.futureValue.keys should contain allOf (
      "reports.teams.t1.unresolved",
      "reports.teams.t2_with_spaces.unresolved"
    )
  }

  "ignore shared repositories" in {

    val leak1 = few(() => aLeakFor("r1", "b1"))
    val leak2 = few(() => aLeakFor("r2", "b1"))
    val leaks = leak1 ::: leak2
    repository.collection.insertMany(leaks).toFuture().futureValue


    val now = Some(LocalDateTime.now)
    val teams: Seq[Team] = Seq(
      Team("T1", now, now, now, Some(Map("services" -> Seq("r1")))),
      Team("T2", now, now, now, Some(Map("services" -> Seq("r2")))))
    when(teamsAndRepositoriesConnector.teamsWithRepositories())
      .thenReturn(Future.successful(teams))

    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq("r1"))

    leaksService.metrics.futureValue should contain allOf (
      "reports.teams.t1.unresolved"    -> 0,
      "reports.teams.t2.unresolved"    -> leak2.length
    )
  }

  def aLeakFor(repo:String, branch:String) =  aLeak.copy(repoName = repo, branch = branch)

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
    "high"
  )

  def aRule = Rule(
    id = "rule",
    scope = Scope.FILE_CONTENT,
    regex = "regex",
    description = "description"
  )

  when(ruleService.getAllRules()).thenReturn(Seq(
    aRule.copy(id = "rule-1"),
    aRule.copy(id = "rule-2"),
    aRule.copy(id = "rule-3")
  ))

}
