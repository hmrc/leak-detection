/*
 * Copyright 2017 HM Revenue & Customs
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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import play.modules.reactivemongo.ReactiveMongoComponent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

class ReportsRepositorySpec
    extends WordSpec
    with Matchers
    with MongoSpecSupport
    with ScalaFutures
    with BeforeAndAfterEach {

  "Reports repository" should {
    "provide a distinct list of repository names only if there were problems" in {
      val reportsWithProblems    = few(() => aReport)
      val reportsWithoutProblems = few(() => aReport).map(_.copy(inspectionResults = Nil))
      val withSomeDuplicates     = reportsWithProblems ::: reportsWithProblems ::: reportsWithoutProblems

      repo.bulkInsert(withSomeDuplicates).futureValue

      val foundNames = repo.getDistinctRepoNames.futureValue
      foundNames should contain theSameElementsAs reportsWithProblems.map(_.repoName)
    }

    "return reports by repository in inverse chronological order" in {
      val increasingTimestamp = {
        var t = DateTime.now(DateTimeZone.UTC)
        () =>
          {
            t = t.plusSeconds(1)
            println(t)
            t
          }
      }

      val repoName = "repo"
      val reports = few(() => {
        aReport.copy(repoName = repoName, timestamp = increasingTimestamp())
      })

      repo.bulkInsert(Random.shuffle(reports)).futureValue

      val foundReports = repo.findByRepoName(repoName).futureValue

      foundReports shouldBe reports.reverse
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
