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

package uk.gov.hmrc.leakdetection.services

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, GivenWhenThen, Matchers, WordSpec}
import play.modules.reactivemongo.ReactiveMongoComponent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import uk.gov.hmrc.leakdetection.ModelFactory
import uk.gov.hmrc.leakdetection.ModelFactory.{aReport, few}
import uk.gov.hmrc.leakdetection.model.LeakResolution
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

class ReportsServiceSpec
    extends WordSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with MongoSpecSupport
    with BeforeAndAfterEach
    with GivenWhenThen {

  "Reports service" should {
    "resolve previous problems if the new report contains no leaks" in {
      val repoName      = "repo"
      val branchName    = "master"
      val anotherBranch = "another-branch"

      def genReports() = few(() => aReport.copy(repoName = repoName, branch = branchName))

      Given("LDS repo contains some outstanding problems for a given branch")
      val outstandingProblems = genReports().map(_.copy(leakResolution = None))

      And("it also contains some already resolved reports")
      val previouslyResolved = genReports().map(_.copy(leakResolution = Some(ModelFactory.aLeakResolution)))

      And("it also contains problems on a different branch")
      val problemsOnAnotherBranch = genReports().map(_.copy(branch = anotherBranch))

      repo.bulkInsert(outstandingProblems ::: previouslyResolved ::: problemsOnAnotherBranch).futureValue

      val reportFixingProblems = genReports().map(_.copy(inspectionResults = Nil)).head

      When(s"a new report is saved that fixes problems on a given branch")
      val _ = reportsService.saveReport(reportFixingProblems).futureValue

      val reportsAfterUpdates = repo.findAll().futureValue

      val reportsPreviouslyWithOutstandingProblems =
        reportsAfterUpdates.filter(r => outstandingProblems.exists(_._id == r._id))

      Then(s"reports with problems are resolved")
      reportsPreviouslyWithOutstandingProblems should not be empty
      val expectedLeakResolution = Some(LeakResolution(reportFixingProblems.timestamp, reportFixingProblems.commitId))
      assert(reportsPreviouslyWithOutstandingProblems.forall(_.leakResolution == expectedLeakResolution))

      And("new report is saved")
      assert(reportsAfterUpdates.contains(reportFixingProblems))

      And("problems on another branch are untouched")
      reportsAfterUpdates.filter(_.branch == anotherBranch) should contain theSameElementsAs problemsOnAnotherBranch

      And("problems already resolved are untouched")
      val alreadyResolvedAfterUpdates = reportsAfterUpdates.filter(r => previouslyResolved.exists(_._id == r._id))
      alreadyResolvedAfterUpdates should contain theSameElementsAs previouslyResolved
    }

    "don't resolve previous problems on the same repo/branch if report still has errors" in {
      val repoName   = "repo"
      val branchName = "master"

      def genReports() = few(() => aReport.copy(repoName = repoName, branch = branchName))

      Given("LDS repo contains some outstanding problems for a given branch")
      val outstandingProblems = genReports().map(_.copy(leakResolution = None))

      repo.bulkInsert(outstandingProblems).futureValue

      val reportStillWithProblems = genReports().head

      When(s"a new report is saved that still indicates problems")
      val _ = reportsService.saveReport(reportStillWithProblems).futureValue

      val reportsAfterUpdates = repo.findAll().futureValue

      val reportsPreviouslyWithOutstandingProblems =
        reportsAfterUpdates.filter(r => outstandingProblems.exists(_._id == r._id))

      Then(s"reports with problems are NOT resolved")
      reportsPreviouslyWithOutstandingProblems should not be empty
      assert(reportsPreviouslyWithOutstandingProblems.forall(_.leakResolution == None))

      And("new report is saved")
      assert(reportsAfterUpdates.contains(reportStillWithProblems))
    }

  }

  override def beforeEach(): Unit =
    repo.drop.futureValue

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  val repo = new ReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

  val reportsService = new ReportsService(repo)

}
