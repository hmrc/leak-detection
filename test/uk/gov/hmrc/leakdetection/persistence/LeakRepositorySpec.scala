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
import uk.gov.hmrc.leakdetection.ModelFactory.aLeak
import uk.gov.hmrc.leakdetection.model.{Leak, LeakUpdateResult}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import scala.concurrent.ExecutionContext.Implicits.global

class LeakRepositorySpec
    extends AnyWordSpec
    with Matchers
    with PlayMongoRepositorySupport[Leak]
    with CleanMongoCollectionSupport
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterEach {

  override val repository = new LeakRepository(mongoComponent)

  "Leak repository" should {

    "return zero inserts and not fail when updating with no leaks" in {
      repository.update("test", "main", Seq.empty).futureValue shouldBe LeakUpdateResult(0, 0)
    }

    "return repositories with unresolved leaks" when {
      "leaks isExcluded is false" in {
        repository.collection
          .insertMany(
            Seq(
              aLeak(repoName = "repo1").copy(isExcluded = false),
              aLeak(repoName = "repo2").copy(isExcluded = true)
            )
          )
          .toFuture()
          .futureValue

        val result = repository.findDistinctRepoNamesWithUnresolvedLeaks().futureValue

        result shouldBe Seq("repo1")
      }
    }
  }
}
