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

import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import org.mongodb.scala.SingleObservableFuture

import java.time.temporal.ChronoUnit.{HOURS, MILLIS}
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LeaksServiceSpec extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[Leak] with MockitoSugar:

  val repository: LeakRepository =
    LeakRepository(mongoComponent)

  lazy val teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector =
    mock[TeamsAndRepositoriesConnector]
    
  lazy val ignoreListConfig: IgnoreListConfig =
    mock[IgnoreListConfig]

  val leaksService: LeaksService =
    LeaksService(repository, teamsAndRepositoriesConnector, ignoreListConfig)

  "Leaks service" should:
    val timestamp: Instant =
      Instant.now().truncatedTo(MILLIS).minus(2, HOURS)

    "get leaks for a report" in:
      repository.collection.insertMany(
        Seq(aLeak.copy(repoName = "repo1", reportId = ReportId("otherReport"), ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture().futureValue

      val results = leaksService.getLeaksForReport(ReportId("reportId")).futureValue

      results shouldBe Seq(
        aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS))
      )

  "produce a metric of the total active leaks" in:

    val leaks = few(() => aLeak)
    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)
    when(teamsAndRepositoriesConnector.teams()).thenReturn(Future.successful(Seq.empty))
    repository.collection.insertMany(leaks).toFuture().futureValue

    leaksService.metrics.futureValue should contain.allOf(
      "reports.total" -> leaks.length,
      "reports.unresolved" -> leaks.length
    )

  "produce metrics grouped by team" in:
    val leaks = List(aLeakFor("r1", "b1"), aLeakFor("r1", "b1"), aLeakFor("r2", "b1"))
    repository.collection.insertMany(leaks).toFuture().futureValue

    when(ignoreListConfig.repositoriesToIgnore)
      .thenReturn(Seq.empty)

    when(teamsAndRepositoriesConnector.teams())
      .thenReturn(Future.successful(Seq(
        TeamsAndRepositoriesConnector.TeamSummary("t1", Some(Instant.now), Seq("r1"))
      , TeamsAndRepositoriesConnector.TeamSummary("t2", Some(Instant.now), Seq("r2"))
      )))

    leaksService.metrics.futureValue should contain.allOf(
      "reports.teams.t1.unresolved" -> 2,
      "reports.teams.t2.unresolved" -> 1
    )

  "normalise team names" in:
    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq.empty)

    when(teamsAndRepositoriesConnector.teams())
      .thenReturn(Future.successful(Seq(
        TeamsAndRepositoriesConnector.TeamSummary("T1", Some(Instant.now), Seq("r1"))
      , TeamsAndRepositoriesConnector.TeamSummary("T2 with spaces", Some(Instant.now), Seq("r2"))
      )))

    leaksService.metrics.futureValue.keys should contain.allOf(
      "reports.teams.t1.unresolved",
      "reports.teams.t2_with_spaces.unresolved"
    )

  "ignore shared repositories" in:
    val leak1 = few(() => aLeakFor("r1", "b1"))
    val leak2 = few(() => aLeakFor("r2", "b1"))
    val leaks = leak1 ::: leak2
    repository.collection.insertMany(leaks).toFuture().futureValue

    when(teamsAndRepositoriesConnector.teams())
      .thenReturn(Future.successful(Seq(
        TeamsAndRepositoriesConnector.TeamSummary("T1", Some(Instant.now), Seq("r1"))
      , TeamsAndRepositoriesConnector.TeamSummary("T2", Some(Instant.now), Seq("r2"))
      )))

    when(ignoreListConfig.repositoriesToIgnore).thenReturn(Seq("r1"))

    leaksService.metrics.futureValue should contain.allOf(
      "reports.teams.t1.unresolved" -> 0,
      "reports.teams.t2.unresolved" -> leak2.length
    )

  def aLeakFor(repo: String, branch: String): Leak =
    aLeak.copy(repoName = repo, branch = branch)

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
