/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, GivenWhenThen, Matchers, WordSpec}
import play.api.libs.json.{Format, JsArray, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.ModelFactory.{aReport, aReportWithResolvedLeaks, few}
import uk.gov.hmrc.leakdetection.model.ResolvedLeak
import uk.gov.hmrc.leakdetection.persistence.{OldReportsRepository, ReportsRepository}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport, ReactiveRepository}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ReportsServiceSpec
    extends WordSpec
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

    "update existing resolved reports (delete leaks, add info about ids and description" in {

      val mongoDateTimeWrites = uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeWrite

      object GenericRepo
          extends ReactiveRepository[JsValue, String](
            collectionName = "reports",
            mongo          = reactiveMongoComponent.mongoConnector.db,
            domainFormat   = implicitly[Format[JsValue]],
            idFormat       = implicitly[Format[String]]
          )

      def report: JsValue =
        Json.obj(
          "_id"       -> UUID.randomUUID().toString,
          "repoName"  -> "n/a",
          "repoUrl"   -> "n/a",
          "commitId"  -> "n/a",
          "branch"    -> "n/a",
          "timestamp" -> mongoDateTimeWrites.writes(DateTimeUtils.now),
          "author"    -> "n/a",
          "inspectionResults" -> JsArray(
            List(
              Json.obj(
                "filePath"    -> "filePath",
                "scope"       -> "scope",
                "lineNumber"  -> 1,
                "urlToSource" -> "url",
                "ruleId"      -> "rule-1",
                "description" -> "descr",
                "lineText"    -> "lineText",
                "matches"     -> JsArray(Nil)
              ),
              Json.obj(
                "filePath"    -> "filePath",
                "scope"       -> "scope",
                "lineNumber"  -> 1,
                "urlToSource" -> "url",
                "ruleId"      -> "rule-1",
                "description" -> "descr",
                "lineText"    -> "lineText",
                "matches"     -> JsArray(Nil)
              ),
              Json.obj(
                "filePath"    -> "filePath",
                "scope"       -> "scope",
                "lineNumber"  -> 1,
                "urlToSource" -> "url",
                "ruleId"      -> "rule-1",
                "description" -> "descr",
                "lineText"    -> "lineText",
                "matches"     -> JsArray(Nil)
              ),
              Json.obj(
                "filePath"    -> "filePath",
                "scope"       -> "scope",
                "lineNumber"  -> 1,
                "urlToSource" -> "url",
                "ruleId"      -> "rule-1",
                "description" -> "descr",
                "lineText"    -> "lineText",
                "matches"     -> JsArray(Nil)
              )
            )),
          "leakResolution" -> Json.obj(
            "timestamp" -> mongoDateTimeWrites.writes(DateTimeUtils.now),
            "commitId"  -> "n/a"
          )
        )

      val toFixCount = 1000

      GenericRepo.bulkInsert((1 to toFixCount).map(_ => report)).futureValue(Timeout(5.minutes))

      oldRepo.countPreviouslyResolvedOldReports().futureValue shouldBe toFixCount

      reportsService.fixPreviousResolvedReports().futureValue(Timeout(5.minutes))

      oldRepo.countPreviouslyResolvedOldReports().futureValue shouldBe 0

    }

  }

  private val reactiveMongoComponent: ReactiveMongoComponent =
    new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

  override def beforeEach(): Unit =
    repo.removeAll().futureValue

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  val repo = new ReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

  val oldRepo = new OldReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

  val reportsService = new ReportsService(repo, oldRepo)

}
