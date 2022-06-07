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

package uk.gov.hmrc.leakdetection.services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.ActiveBranchesRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import java.time.temporal.ChronoUnit.{HOURS, MILLIS}
import scala.concurrent.ExecutionContext.Implicits.global

class ActiveBranchesServiceSpec extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[ActiveBranch] {

  override val repository = new ActiveBranchesRepository(mongoComponent)

  val service = new ActiveBranchesService(repository)
  "active branches service" should {
    "get active branches for all repos" in {
      val activeBranches = Seq(
        anActiveBranch,
        anActiveBranch.copy(branch   = "other branch"),
        anActiveBranch.copy(repoName = "other repo", branch = "main")
      )
      repository.collection.insertMany(activeBranches).toFuture().futureValue

      val result = service.getActiveBranches(None).futureValue

      result.length                   shouldBe 3
      result.map(_.repoName).distinct should contain theSameElementsAs Seq("repo", "other repo")
      result.map(_.branch)            should contain theSameElementsAs Seq("branch", "other branch", "main")
    }
    "get all active branches for a given repository" in {
      val activeBranches = Seq(
        anActiveBranch,
        anActiveBranch.copy(branch   = "other branch"),
        anActiveBranch.copy(repoName = "other repo", branch = "main")
      )
      repository.collection.insertMany(activeBranches).toFuture().futureValue

      val result = service.getActiveBranches(Some("repo")).futureValue

      result.length        shouldBe 2
      result.map(_.branch) should contain theSameElementsAs Seq("branch", "other branch")
    }
    "remove active branches" in {
      repository.collection.insertOne(anActiveBranch).toFuture().futureValue
      repository.collection.countDocuments().toFuture().futureValue shouldBe 1

      service.clearBranch("repo", "branch").futureValue

      repository.collection.countDocuments().toFuture().futureValue shouldBe 0
    }
    "mark active branches" when {
      "active branch does not exit" in {
        service.markAsActive(Repository("repo"), Branch("branch"), ReportId("reportId")).futureValue

        val persistedValues = repository.collection.find().toFuture().futureValue
        persistedValues.length shouldBe 1
        val activeBranch = persistedValues.head
        activeBranch.repoName shouldBe "repo"
        activeBranch.branch   shouldBe "branch"
        activeBranch.reportId shouldBe "reportId"
        activeBranch.created  shouldBe activeBranch.updated
      }
      "active branch already exists" in {
        val originalInstant = Instant.now().truncatedTo(MILLIS).minus(5, HOURS)
        repository.collection
          .insertOne(anActiveBranch.copy(created = originalInstant, updated = originalInstant))
          .toFuture()
          .futureValue

        service.markAsActive(Repository("repo"), Branch("branch"), ReportId("reportId")).futureValue

        val persistedValues = repository.collection.find().toFuture().futureValue
        persistedValues.length shouldBe 1
        val activeBranch = persistedValues.head
        activeBranch.repoName shouldBe "repo"
        activeBranch.branch   shouldBe "branch"
        activeBranch.reportId shouldBe "reportId"
        activeBranch.created  shouldBe originalInstant
        activeBranch.updated  should be > originalInstant
      }
    }
  }

  def anActiveBranch = ActiveBranch("repo", "branch", "reportId")
}
