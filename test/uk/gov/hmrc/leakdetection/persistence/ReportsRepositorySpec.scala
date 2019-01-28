/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.persistence

import org.scalatest.concurrent.ScalaFutures

import concurrent.duration._
import org.scalatest._
import play.api.libs.json.{Format, JsArray, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.{Report, ReportId}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport, ReactiveRepository}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class ReportsRepositorySpec
    extends WordSpec
    with Matchers
    with MongoSpecSupport
    with ScalaFutures
    with BeforeAndAfterEach
    with IncreasingTimestamps {

  "Reports repository" should {
    "provide a distinct list of repository names only if there were unresolved problems" in {
      val reportsWithUnresolvedProblems = few(() => aReportWithLeaks())
      val reportsWithResolvedProblems   = few(() => aReportWithResolvedLeaks())
      val reportsWithoutProblems        = few(() => aReportWithoutLeaks())
      val withSomeDuplicates =
        reportsWithResolvedProblems :::
          reportsWithUnresolvedProblems :::
          reportsWithUnresolvedProblems :::
          reportsWithoutProblems

      repo.bulkInsert(withSomeDuplicates).futureValue

      val foundNames = repo.getDistinctRepoNames.futureValue
      foundNames should contain theSameElementsAs reportsWithUnresolvedProblems.map(_.repoName)
    }

    "return reports by repository in inverse chronological order" in {
      val repoName = "repo"
      val reports: Seq[Report] = few(() => {
        aReportWithLeaks(repoName).copy(timestamp = increasingTimestamp())
      })

      repo.bulkInsert(Random.shuffle(reports)).futureValue

      val foundReports: Seq[Report] = repo.findUnresolvedWithProblems(repoName).futureValue

      foundReports shouldBe reports.reverse
    }

    "only return reports that actually had unresolved problems in them" in {
      val repoName                      = "repo"
      val reportsWithUnresolvedProblems = few(() => aReportWithLeaks(repoName))
      val reportsWithResolvedProblems   = few(() => aReportWithResolvedLeaks(repoName))
      val reportsWithoutProblems        = few(() => aReportWithoutLeaks(repoName))

      val all = reportsWithUnresolvedProblems ::: reportsWithResolvedProblems ::: reportsWithoutProblems

      repo.bulkInsert(all).futureValue

      val foundReports = repo.findUnresolvedWithProblems(repoName).futureValue

      foundReports should contain theSameElementsAs reportsWithUnresolvedProblems
    }

    "read reports with missing resolved leaks (testing backwards compatibility)" in {

      val mongoDateTimeWrites = uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeWrite

      object GenericRepo
          extends ReactiveRepository[JsValue, String](
            collectionName = "reports",
            mongo          = reactiveMongoComponent.mongoConnector.db,
            domainFormat   = implicitly[Format[JsValue]],
            idFormat       = implicitly[Format[String]]
          )

      val leakResWithoutResolvedLeaks = Json.obj(
        "_id"       -> "n/a",
        "timestamp" -> mongoDateTimeWrites.writes(DateTimeUtils.now),
        "commitId"  -> "n/a"
      )
      val reportId = "id"
      val report: JsValue =
        Json.obj(
          "_id"               -> reportId,
          "repoName"          -> "n/a",
          "repoUrl"           -> "n/a",
          "commitId"          -> "n/a",
          "branch"            -> "n/a",
          "timestamp"         -> mongoDateTimeWrites.writes(DateTimeUtils.now),
          "author"            -> "n/a",
          "inspectionResults" -> JsArray(Nil),
          "leakResolution"    -> leakResWithoutResolvedLeaks
        )

      GenericRepo.insert(report).futureValue

      noException shouldBe thrownBy {
        repo.findByReportId(ReportId(reportId)).futureValue
      }
    }

    "produce stats grouped by repository" in {
      val reports = List(aReportWithResolvedLeaks("r1"), aReport("r2"), aReport("r1"), aReportWithResolvedLeaks("r3"))
      repo.bulkInsert(reports).futureValue

      repo.howManyUnresolvedByRepository().futureValue shouldBe Map("r1" -> 1, "r2" -> 1)
    }

  }

  override def beforeEach(): Unit = dropCollectionWithRetries()

  // Sometimes dropping fails with error complaining about background operations still in progress
  private def dropCollectionWithRetries(maxRetries: Int = 5): Unit = {
    if (!repo.drop.futureValue && maxRetries > 0) {
      dropCollectionWithRetries(maxRetries - 1)
    }
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  private val reactiveMongoComponent: ReactiveMongoComponent =
    new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }
  val repo = new ReportsRepository(reactiveMongoComponent)

}
