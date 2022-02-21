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

package uk.gov.hmrc.leakdetection.persistence

import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.{Report, Repository}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class ReportsRepositorySpec
    extends AnyWordSpec
    with Matchers
    //with DefaultPlayMongoRepositorySupport[Report] // TODO we have non-indexed queries...
    with PlayMongoRepositorySupport[Report]
    with CleanMongoCollectionSupport
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterEach
    with IncreasingTimestamps {

  "Reports repository" should {

    "return reports by repository in inverse chronological order" in {
      val repoName = "repo"
      val reports: Seq[Report] = few(() => {
        aReportWithLeaks(repoName).copy(timestamp = increasingTimestamp())
      })

      repository.collection.insertMany(Random.shuffle(reports)).toFuture.futureValue

      val foundReports: Seq[Report] = repository.findUnresolvedWithProblems(Repository(repoName)).futureValue

      foundReports shouldBe reports.reverse
    }

    "only return reports that actually had unresolved problems in them" in {
      val repoName                      = "repo"
      val reportsWithUnresolvedProblems = few(() => aReportWithLeaks(repoName))
      val reportsWithoutProblems        = few(() => aReportWithoutLeaks(repoName))

      val all = reportsWithUnresolvedProblems ::: reportsWithoutProblems

      repository.collection.insertMany(all).toFuture.futureValue

      val foundReports = repository.findUnresolvedWithProblems(Repository(repoName)).futureValue

      foundReports should contain theSameElementsAs reportsWithUnresolvedProblems
    }

  }

  override val repository = new ReportsRepository(mongoComponent)
}
