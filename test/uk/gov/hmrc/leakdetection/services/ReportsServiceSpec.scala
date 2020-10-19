/*
 * Copyright 2020 HM Revenue & Customs
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

import java.time.LocalDateTime

import org.mockito.Mockito.when
import org.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, GivenWhenThen}
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.ModelFactory.{aReport, aReportWithResolvedLeaks, few}
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.ResolvedLeak
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class ReportsServiceSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with MongoSpecSupport
    with BeforeAndAfterEach
    with GivenWhenThen
    with IncreasingTimestamps {

  "Reports service" should {
    "resolve previous problems if the new report contains no leaks" in {
      val repoName      = "repo"
      val branchName    = "master"
      val anotherBranch = "another-branch"

      def genReports() = few(() => aReport(repoName).copy(branch = branchName))

      Given("LDS repo contains some outstanding problems for a given branch")
      val reportsWithLeaks = genReports().map(_.copy(leakResolution = None))

      And("it also contains some already resolved reports")
      val reportsWithPreviouslyResolvedLeaks = few(() => aReportWithResolvedLeaks())

      And("it also contains problems on a different branch")
      val reportsWithLeaksAnotherBranch = genReports().map(_.copy(branch = anotherBranch))

      repo
        .bulkInsert(reportsWithLeaks ::: reportsWithPreviouslyResolvedLeaks ::: reportsWithLeaksAnotherBranch)
        .futureValue

      val reportFixingLeaks = genReports().map(_.copy(inspectionResults = Nil)).head

      When("a new report is saved that fixes problems on a given branch")
      val _ = reportsService.saveReport(reportFixingLeaks).futureValue

      val reportsAfterUpdates = repo.findAll().futureValue

      val reportsWithResolvedLeaks =
        reportsAfterUpdates.filter(r => reportsWithLeaks.exists(_._id == r._id))

      Then(s"reports with problems are resolved")
      reportsWithResolvedLeaks should not be empty

      And("they no longer contain secrets")
      assert(reportsWithResolvedLeaks.forall(_.inspectionResults.isEmpty))

      And("they contain the summaries (ids/descriptions) of the resolved leaks")
      reportsWithLeaks.zip(reportsWithResolvedLeaks).foreach {
        case (reportWithLeaks, reportWithResolvedLeaks) =>
          reportWithResolvedLeaks.leakResolution.get.timestamp shouldBe reportFixingLeaks.timestamp
          reportWithResolvedLeaks.leakResolution.get.commitId  shouldBe reportFixingLeaks.commitId

          val originalLeaks =
            reportWithLeaks.inspectionResults.map { reportLine =>
              ResolvedLeak(ruleId = reportLine.ruleId.getOrElse(""), description = reportLine.description)
            }

          val resolvedLeaks = reportWithResolvedLeaks.leakResolution.toList.flatMap(_.resolvedLeaks)

          originalLeaks should contain theSameElementsAs resolvedLeaks

      }

      And("new report is saved")
      assert(reportsAfterUpdates.contains(reportFixingLeaks))

      And("problems on another branch are untouched")
      reportsAfterUpdates.filter(_.branch == anotherBranch) should contain theSameElementsAs reportsWithLeaksAnotherBranch

      And("problems already resolved are untouched")
      val alreadyResolvedAfterUpdates =
        reportsAfterUpdates.filter(r => reportsWithPreviouslyResolvedLeaks.exists(_._id == r._id))
      alreadyResolvedAfterUpdates should contain theSameElementsAs reportsWithPreviouslyResolvedLeaks
    }

    "don't resolve previous problems on the same repo/branch if report still has errors" in {
      val repoName   = "repo"
      val branchName = "master"

      def genReports() = few(() => aReport(repoName).copy(branch = branchName))

      Given("LDS repo contains some outstanding problems for a given branch")
      val reportsWithLeaks = genReports().map(_.copy(leakResolution = None))

      repo.bulkInsert(reportsWithLeaks).futureValue

      val reportWithLeaks = genReports().head

      When(s"a new report is saved that still indicates problems")
      val _ = reportsService.saveReport(reportWithLeaks).futureValue

      val reportsAfterUpdates = repo.findAll().futureValue

      val reportsWithLeaksAfterUpdates =
        reportsAfterUpdates.filter(r => reportsWithLeaks.exists(_._id == r._id))

      Then(s"reports with problems are NOT resolved")
      reportsWithLeaksAfterUpdates should not be empty
      assert(reportsWithLeaksAfterUpdates.forall(_.leakResolution.isEmpty))

      And("new report is saved")
      assert(reportsAfterUpdates.contains(reportWithLeaks))
    }

    "provide a list of reports for a repo showing only the latest one per branch" in {
      val repoName = "repo"
      val branch1  = "master"
      val branch2  = "another-branch"

      def genReport(branchName: String) =
        aReport(repoName).copy(branch = branchName, timestamp = increasingTimestamp())

      val reportsWithLeaksBranch1 =
        List(
          genReport(branch1).copy(leakResolution = None),
          genReport(branch1).copy(leakResolution = None)
        )

      val reportsWithResolvedLeaksBranch2 =
        List(
          aReportWithResolvedLeaks().copy(branch = branch2, timestamp = increasingTimestamp())
        )

      repo.bulkInsert(reportsWithLeaksBranch1 ::: reportsWithResolvedLeaksBranch2).futureValue

      val expectedResult = reportsWithLeaksBranch1.last :: Nil
      reportsService.getLatestReportsForEachBranch(repoName).futureValue should contain theSameElementsAs expectedResult
    }

    "produce metrics" in {
      val repoName   = "repo"
      val branchName = "master"

      def genReports() = few(() => aReport(repoName).copy(branch = branchName))

      Given("LDS repo contains some outstanding problems for a given branch")
      val reportsWithLeaks = genReports().map(_.copy(leakResolution = None))

      And("it also contains some already resolved reports")
      val reportsWithPreviouslyResolvedLeaks = few(() => aReportWithResolvedLeaks())

      repo
        .bulkInsert(reportsWithLeaks ::: reportsWithPreviouslyResolvedLeaks)
        .futureValue

      reportsService.metrics.futureValue should contain allOf (
        "reports.total"                  -> (reportsWithLeaks.size + reportsWithPreviouslyResolvedLeaks.size),
        "reports.unresolved"             -> reportsWithLeaks.size,
        "reports.resolved"               -> reportsWithPreviouslyResolvedLeaks.size
      )
    }

    "produce metrics grouped by team" in {

      val reports = List(aReport("r1"), aReport("r2"), aReport("r1"))
      repo.bulkInsert(reports).futureValue

      val now = Some(LocalDateTime.now)
      val teams: Seq[Team] = Seq(
        Team("t1", now, now, now, Some(Map("services" -> Seq("r1")))),
        Team("t2", now, now, now, Some(Map("services" -> Seq("r2")))))
      when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(teams))

      reportsService.metrics.futureValue should contain allOf (
        "reports.teams.t1.unresolved"    -> 2,
        "reports.teams.t2.unresolved"    -> 1
      )
    }

    "normalise team names" in {

      val reports = List(aReport("r1"), aReport("r2"), aReport("r1"))
      repo.bulkInsert(reports).futureValue

      val now = Some(LocalDateTime.now)
      val teams: Seq[Team] = Seq(
        Team("T1", now, now, now, Some(Map("services"             -> Seq("r1")))),
        Team("T2 with spaces", now, now, now, Some(Map("services" -> Seq("r2")))))
      when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(teams))

      reportsService.metrics.futureValue          should contain allOf (
        "reports.teams.t1.unresolved"             -> 2,
        "reports.teams.t2_with_spaces.unresolved" -> 1
      )
    }

    "ignore shared repositories" in {

      val reports = List(aReport("r1"), aReport("r2"), aReport("r1"))
      repo.bulkInsert(reports).futureValue

      val now = Some(LocalDateTime.now)
      val teams: Seq[Team] = Seq(
        Team("T1", now, now, now, Some(Map("services" -> Seq("r1")))),
        Team("T2", now, now, now, Some(Map("services" -> Seq("r2")))))
      when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(teams))

      val reportsService =
        new ReportsService(repo, teamsAndRepositoriesConnector, Configuration("shared.repositories.0" -> "r1"))
      reportsService.metrics.futureValue should contain allOf (
        "reports.teams.t1.unresolved"    -> 0,
        "reports.teams.t2.unresolved"    -> 1
      )
    }

  }

  override def beforeEach(): Unit =
    repo.removeAll().futureValue

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  val repo = new ReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

  private val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  when(teamsAndRepositoriesConnector.teamsWithRepositories()).thenReturn(Future.successful(Seq.empty))

  val reportsService = new ReportsService(repo, teamsAndRepositoriesConnector, Configuration())

}
