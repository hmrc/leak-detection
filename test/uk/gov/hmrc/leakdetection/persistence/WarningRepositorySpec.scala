/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.leakdetection.ModelFactory.few
import uk.gov.hmrc.leakdetection.model.{LeakUpdateResult, ReportId, Warning}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class WarningRepositorySpec extends AnyWordSpec
  with Matchers
  with PlayMongoRepositorySupport[Warning]
  with CleanMongoCollectionSupport
  with ScalaFutures
  with IntegrationPatience
  with BeforeAndAfterEach {

  override val repository = new WarningRepository(mongoComponent)

  "Warning repository" when {
    "updating warnings" should {
      "return zero inserts and not fail when updating with no warnings" in {
        val result = repository.update("repo", "branch", Seq.empty).futureValue

        result shouldBe LeakUpdateResult(0, 0)
      }

      "clear up any previously stored warnings during an update" in {
        val warnings = givenSomeWarningsLike(aWarning)

        val expected = Seq(aWarning.copy(reportId = ReportId("report2")))
        val result = repository.update("repo", "branch", expected).futureValue

        result shouldBe LeakUpdateResult(1, warnings.length)

        repository.collection.find().toFuture().futureValue shouldBe expected
      }
    }
    "finding warnings" should {
      "find all warnings for a particular repository and branch" in {
        givenSomeWarningsLike(aWarning.copy(branch = "other"))
        givenSomeWarningsLike(aWarning.copy(repoName = "other"))
        val warnings = givenSomeWarningsLike(aWarning)

        val result = repository.findBy(Some("repo"), Some("branch")).futureValue

        result shouldBe warnings
      }

      "find all warnings for a particular repository" in {
        givenSomeWarningsLike(aWarning.copy(repoName = "other"))
        val warnings = givenSomeWarningsLike(aWarning)
        val otherBranchWarnings = givenSomeWarningsLike(aWarning.copy(branch = "other"))

        val result = repository.findBy(Some("repo"), None).futureValue

        result shouldBe otherBranchWarnings ++ warnings
      }

      "only filter by branch if repoName also supplied" in {
        val warnings = givenSomeWarningsLike(aWarning)
        val otherBranchWarnings = givenSomeWarningsLike(aWarning.copy(branch = "other"))
        val otherRepoWarnings = givenSomeWarningsLike(aWarning.copy(repoName = "other"))

        val result = repository.findBy(None, Some("branch")).futureValue

        result shouldBe warnings ++ otherBranchWarnings ++ otherRepoWarnings
      }

      "find all warnings for a particular report" in {
        givenSomeWarningsLike(aWarning.copy(reportId = ReportId("other")))
        val warnings = givenSomeWarningsLike(aWarning)

        val result = repository.findForReport("report").futureValue

        result shouldBe warnings
      }
    }
  }

  def aWarning = Warning("repo", "branch", Instant.now().truncatedTo(ChronoUnit.MILLIS), ReportId("report"), "message")

  def givenSomeWarningsLike(warning: Warning): Seq[Warning] = {
    val warnings: Seq[Warning] = few(() => {
      warning
    })
    repository.collection.insertMany(Random.shuffle(warnings)).toFuture()
    repository.collection.find().toFuture().futureValue
    warnings
  }
}
