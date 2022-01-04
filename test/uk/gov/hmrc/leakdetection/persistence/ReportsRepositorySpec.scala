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
import uk.gov.hmrc.leakdetection.model.{Report, ReportId, Repository}
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
    "provide a distinct list of repository names only if there were unresolved problems" in {
      val reportsWithUnresolvedProblems = few(() => aReportWithLeaks())
      val reportsWithResolvedProblems   = few(() => aReportWithResolvedLeaks())
      val reportsWithoutProblems        = few(() => aReportWithoutLeaks())
      val withSomeDuplicates =
        reportsWithResolvedProblems :::
          reportsWithUnresolvedProblems :::
          reportsWithUnresolvedProblems.map(_.copy(id = ReportId.random)) :::
          reportsWithoutProblems

      repository.collection.insertMany(withSomeDuplicates).toFuture.futureValue

      val foundNames = repository.getDistinctRepoNames.futureValue
      foundNames should contain theSameElementsAs reportsWithUnresolvedProblems.map(_.repoName)
    }

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
      val reportsWithResolvedProblems   = few(() => aReportWithResolvedLeaks(repoName))
      val reportsWithoutProblems        = few(() => aReportWithoutLeaks(repoName))

      val all = reportsWithUnresolvedProblems ::: reportsWithResolvedProblems ::: reportsWithoutProblems

      repository.collection.insertMany(all).toFuture.futureValue

      val foundReports = repository.findUnresolvedWithProblems(Repository(repoName)).futureValue

      foundReports should contain theSameElementsAs reportsWithUnresolvedProblems
    }

    "produce stats grouped by repository" in {
      val reports = List(aReportWithResolvedLeaks("r1"), aReport("r2"), aReport("r1"), aReportWithResolvedLeaks("r3"))
      repository.collection.insertMany(reports).toFuture.futureValue

      repository.howManyUnresolvedByRepository().futureValue shouldBe Map("r1" -> 1, "r2" -> 1)
    }
  }

  override val repository = new ReportsRepository(mongoComponent)
}
