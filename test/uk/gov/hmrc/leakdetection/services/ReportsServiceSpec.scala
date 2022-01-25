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
import uk.gov.hmrc.leakdetection.ModelFactory.{aLeak, aMatchedResult, aReport, aReportWithoutLeaks, few}
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, PlayConfigLoader}
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.{Branch, Report, ReportId, Repository}
import uk.gov.hmrc.leakdetection.persistence.{LeakRepository, ReportsRepository}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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

  "Reports service" should {

    "provide a list of reports for a repo showing only the latest one per branch" in {
      val repoName = "repo"
      val branch1  = "main"

      def genReport(branchName: String) =
        aReport(repoName).copy(branch = branchName, timestamp = increasingTimestamp())

      val reportsWithLeaksBranch1 =
        List(
          genReport(branch1),
          genReport(branch1)
        )


      repository.collection.insertMany(reportsWithLeaksBranch1).toFuture.futureValue

      val expectedResult = reportsWithLeaksBranch1.last :: Nil
      reportsService.getLatestReportsForEachBranch(Repository(repoName)).futureValue should contain theSameElementsAs expectedResult
    }


  }

  override val repository = new ReportsRepository(mongoComponent)

  private val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  when(teamsAndRepositoriesConnector.teamsWithRepositories())
    .thenReturn(Future.successful(Seq.empty))
  implicit val hc = new HeaderCarrier()
  private val githubService = mock[GithubService]
  when(githubService.getDefaultBranchName(Repository(any))(any, any)).thenReturn(Future.successful(Branch.main))

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
  val reportsService = new ReportsService(repository, configuration, githubService)
}
