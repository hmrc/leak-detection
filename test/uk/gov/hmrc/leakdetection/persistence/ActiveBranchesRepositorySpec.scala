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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.model.ActiveBranch
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class ActiveBranchesRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DefaultPlayMongoRepositorySupport[ActiveBranch] {

  override val repository = new ActiveBranchesRepository(mongoComponent)

  "Active branches repository" when {
    "creating" should {
      "add a new entry" in {
        val activeBranch = anActiveBranch

        repository.create(activeBranch).futureValue

        repository.collection.find().toFuture().futureValue shouldBe Seq(activeBranch)
      }
    }

    "updating" should {
      "replace an existing entry" in {
        repository.collection.insertOne(anActiveBranch).toFuture().futureValue

        val activeBranch = anActiveBranch.copy(reportId = "new report id")

        repository.update(activeBranch).futureValue

        repository.collection.find().toFuture().futureValue shouldBe Seq(activeBranch)
      }
    }

    "deleting" should {
      "remove an existing entry" in {
        repository.collection.insertOne(anActiveBranch).toFuture().futureValue
        repository.collection.countDocuments().toFuture().futureValue shouldBe 1

        repository.delete("repo", "branch").futureValue

        repository.collection.countDocuments().toFuture().futureValue shouldBe 0
      }
    }

    "finding" should {
      "find by repository and branch" when {
        "document exists" in {
          val activeBranch = anActiveBranch
          repository.collection.insertOne(activeBranch).toFuture().futureValue

          val result = repository.find("repo", "branch").futureValue

          result shouldBe Some(activeBranch)
        }
        "document does not exist" in {
          val result = repository.find("repo", "branch").futureValue

          result shouldBe None
        }
      }
      "find by repository" when {
        "multiple documents exist" in {
          val activeBranches = Seq(
            anActiveBranch,
            anActiveBranch.copy(branch   = "other branch"),
            anActiveBranch.copy(repoName = "other repo", branch = "main")
          )
          repository.collection.insertMany(activeBranches).toFuture().futureValue

          val result = repository.findForRepo("repo").futureValue

          result.length        shouldBe 2
          result.map(_.branch) shouldBe Seq("branch", "other branch")
        }
        "no documents exist" in {
          val result = repository.findForRepo("repo").futureValue

          result shouldBe Seq.empty
        }
      }
    }
  }

  def anActiveBranch = ActiveBranch("repo", "branch", "reportId")

}
