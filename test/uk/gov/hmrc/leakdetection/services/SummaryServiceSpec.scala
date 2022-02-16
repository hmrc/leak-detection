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
import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.{LeakRepository, WarningRepository}
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SummaryServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with DefaultPlayMongoRepositorySupport[Leak] {

  val repository = new LeakRepository(mongoComponent)
  val warningRepository = new WarningRepository(mongoComponent)

  lazy val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  lazy val ruleService = mock[RuleService]
  lazy val ignoreListConfig = mock[IgnoreListConfig]

  val service = new SummaryService(ruleService, repository, warningRepository, teamsAndRepositoriesConnector)

  def givenSomeLeaks(timestamp: Instant) = repository.collection.insertMany(
    Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
      aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
      aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
      aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS))
    )).toFuture.futureValue

  def givenSomeWarnings(timestamp: Instant) = warningRepository.collection.insertMany(
    Seq(aWarning.copy(repoName = "repo1", branch = "other", timestamp = timestamp),
      aWarning.copy(repoName = "repo1", branch = "other", timestamp = timestamp),
      aWarning.copy(repoName = "repo2", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
      aWarning.copy(repoName = "repo3", timestamp = timestamp),
      aWarning.copy(repoName = "repo3", branch = "branch1", timestamp = timestamp.minus(1, HOURS))
    )).toFuture.futureValue

  "summary service" should {
    val timestamp = Instant.now.minus(2, HOURS)
    "generate summaries by repository, branch and rule" should {
      when(ruleService.getAllRules()).thenReturn(Seq(
        aRule.copy(id = "rule-1"),
        aRule.copy(id = "rule-2"),
        aRule.copy(id = "rule-3")
      ))

      "include details when just leaks exist" in {
        givenSomeLeaks(timestamp)

        val results = service.getSummaries(None, None, None).futureValue

        results shouldBe Summary(Seq(
          aRule.copy(id = "rule-1"),
          aRule.copy(id = "rule-2"),
          aRule.copy(id = "rule-3")
        ), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 0, 2, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 0, 2, Map("rule-1" -> 1, "rule-2" -> 1))
          )),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 0, 2, Seq(
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 0, 1, Map("rule-1" -> 1)),
            BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 0, 1, Map("rule-1" -> 1))
          ))
        ))
      }

      "include details when just warnings exist" in {
        givenSomeWarnings(timestamp)

        val results = service.getSummaries(None, None, None).futureValue

        results shouldBe Summary(Seq(
          aRule.copy(id = "rule-1"),
          aRule.copy(id = "rule-2"),
          aRule.copy(id = "rule-3")
        ), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 0, Seq(
            BranchSummary("other", ReportId("reportId"), timestamp, 2, 0, Map.empty)
          )),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(3, HOURS), 1, 0, Seq(
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1, 0, Map.empty)
          )),
          RepositorySummary("repo3", timestamp.minus(1, HOURS), timestamp, 2, 0, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 1, 0, Map.empty),
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(1, HOURS), 1, 0, Map.empty)
          ))
        ))
      }

      "include all details when both leaks and warnings exist and no filters applied" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results = service.getSummaries(None, None, None).futureValue

        results shouldBe Summary(Seq(
          aRule.copy(id = "rule-1"),
          aRule.copy(id = "rule-2"),
          aRule.copy(id = "rule-3")
        ), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 2, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 0, 2, Map("rule-1" -> 1, "rule-2" -> 1)),
            BranchSummary("other", ReportId("reportId"), timestamp, 2, 0, Map.empty))
          ),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 1, 2, Seq(
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1, 1, Map("rule-1" -> 1)),
            BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 0, 1, Map("rule-1" -> 1))
          )),
          RepositorySummary("repo3", timestamp.minus(1, HOURS), timestamp, 2, 0, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 1, 0, Map.empty),
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(1, HOURS), 1, 0, Map.empty)
          ))
        ))
      }

      "only include leaks associated to the rule if ruleId is provided" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results = service.getSummaries(Some("rule-1"), None, None).futureValue

        results shouldBe Summary(Seq(
          aRule.copy(id = "rule-1"),
          aRule.copy(id = "rule-2"),
          aRule.copy(id = "rule-3")
        ), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 1, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 0, 1, Map("rule-1" -> 1)),
            BranchSummary("other", ReportId("reportId"), timestamp, 2, 0, Map.empty)
          )),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 1, 2, Seq(
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1, 1, Map("rule-1" -> 1)),
            BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 0, 1, Map("rule-1" -> 1))
          )),
          RepositorySummary("repo3", timestamp.minus(1, HOURS), timestamp, 2, 0, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 1, 0, Map.empty),
            BranchSummary("branch1", ReportId("reportId"), timestamp.minus(1, HOURS), 1, 0, Map.empty)
          ))
        )
        )
      }

      "only include details associated to the repository if repoName is provided" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        val results = service.getSummaries(None, Some("repo1"), None).futureValue

        results shouldBe Summary(Seq(aRule.copy(id = "rule-1"),
          aRule.copy(id = "rule-2"),
          aRule.copy(id = "rule-3")
        ), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 1, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 0, 1, Map("rule-1" -> 1)),
            BranchSummary("other", ReportId("reportId"), timestamp, 2, 0, Map.empty)
          ))
        ))
      }

      "only include details associated to the team if teamName is provided" in {
        givenSomeLeaks(timestamp)
        givenSomeWarnings(timestamp)

        when(teamsAndRepositoriesConnector.team(mockEq("team1")))
          .thenReturn(Future.successful(Option(Team("team1", None, None, None, Some(Map("Service" -> Seq("repo1")))))))

        when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

        val results = service.getSummaries(None, None, Some("team1")).futureValue

        results shouldBe Summary(Seq(
          aRule.copy(id = "rule-1"),
          aRule.copy(id = "rule-2"),
          aRule.copy(id = "rule-3")
        ), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 2, 2, Seq(
            BranchSummary("branch", ReportId("reportId"), timestamp, 0, 2, Map("rule-1" -> 1, "rule-2" -> 1)),
            BranchSummary("other", ReportId("reportId"), timestamp, 2, 0, Map.empty)
          ))
        ))
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
    "high"
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

  when(ruleService.getAllRules()).thenReturn(Seq(
    aRule.copy(id = "rule-1"),
    aRule.copy(id = "rule-2"),
    aRule.copy(id = "rule-3")
  ))
}
