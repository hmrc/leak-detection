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

package uk.gov.hmrc.leakdetection.persistence

import concurrent.duration._
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest._
import play.modules.reactivemongo.ReactiveMongoComponent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

class ReportsRepositorySpec
    extends WordSpec
    with Matchers
    with MongoSpecSupport
    with ScalaFutures
    with BeforeAndAfterEach
    with IncreasingTimestamps {

  "Reports repository" should {
    "provide a distinct list of repository names only if there were unresolved problems" in {
      val reportsWithUnresolvedProblems = few(() => aReportWithUnresolvedProblems())
      val reportsWithResolvedProblems   = few(() => aReportWithResolvedProblems())
      val reportsWithoutProblems        = few(() => aReportWithProblems()).map(_.copy(inspectionResults = Nil))
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
        aReportWithUnresolvedProblems(repoName).copy(timestamp = increasingTimestamp())
      })

      repo.bulkInsert(Random.shuffle(reports)).futureValue

      val foundReports: Seq[Report] = repo.findUnresolvedWithProblems(repoName).futureValue

      foundReports shouldBe reports.reverse
    }

    "only return reports that actually had unresolved problems in them" in {
      val repoName                      = "repo"
      val reportsWithUnresolvedProblems = few(() => aReportWithUnresolvedProblems(repoName))
      val reportsWithResolvedProblems   = few(() => aReportWithResolvedProblems(repoName))
      val reportsWithoutProblems        = few(() => aReportWithoutProblems(repoName))

      val all = reportsWithUnresolvedProblems ::: reportsWithResolvedProblems ::: reportsWithoutProblems

      repo.bulkInsert(all).futureValue

      val foundReports = repo.findUnresolvedWithProblems(repoName).futureValue

      foundReports should contain theSameElementsAs reportsWithUnresolvedProblems
    }

  }

  override def beforeEach(): Unit =
    repo.drop.futureValue

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  val repo = new ReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

}
