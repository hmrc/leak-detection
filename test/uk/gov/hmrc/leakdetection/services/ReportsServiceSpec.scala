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


import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, GivenWhenThen}
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, PlayConfigLoader}
import uk.gov.hmrc.leakdetection.model.{DeleteBranchEvent, Report}
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import scala.concurrent.ExecutionContext.Implicits.global

class ReportsServiceSpec
    extends AnyWordSpec
    with Matchers
    with ArgumentMatchersSugar
    with ScalaFutures
    with IntegrationPatience
    with MockitoSugar
    //with DefaultPlayMongoRepositorySupport[Report] // TODO we have non-indexed queries...
    with PlayMongoRepositorySupport[Report]
    with CleanMongoCollectionSupport
    with BeforeAndAfterEach
    with GivenWhenThen
    with IncreasingTimestamps {

  override val repository = new ReportsRepository(mongoComponent)

  implicit val hc = new HeaderCarrier()

  private val configuration: Configuration = Configuration(
    "githubSecrets.personalAccessToken"      -> "PLACEHOLDER",
    "githubSecrets.webhookSecretKey"         -> "PLACEHOLDER",
    "github.url"                             -> "url",
    "github.apiUrl"                          -> "url",
    "allRules.privateRules"                  -> List(),
    "allRules.publicRules"                   -> List(),
    "leakResolutionUrl"                      -> "PLACEHOLDER",
    "maxLineLength"                          -> 2147483647,
    "clearingCollectionEnabled"              -> false)
  val configLoader: ConfigLoader = new PlayConfigLoader(configuration)
  val reportsService = new ReportsService(repository, configuration)


  "Reports service" should {

    "insert a report with no leaks when a branch is deleted" in {

      val branchDeleteEvent = DeleteBranchEvent("repo", "test.user", "branch1", true, "http://repo.url/repo/branch1")

      val expectedResult = reportsService.clearReportsAfterBranchDeleted(branchDeleteEvent).futureValue

      expectedResult.repoName      shouldBe branchDeleteEvent.repositoryName
      expectedResult.branch        shouldBe branchDeleteEvent.branchRef
      expectedResult.repoUrl       shouldBe branchDeleteEvent.repositoryUrl
      expectedResult.totalLeaks    shouldBe 0
      expectedResult.commitId      shouldBe "n/a (branch was deleted)"
      expectedResult.author        shouldBe branchDeleteEvent.authorName
      expectedResult.rulesViolated shouldBe Map.empty

      repository.findByReportId(expectedResult.id).futureValue should not be None
    }
  }

}
