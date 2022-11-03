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

package uk.gov.hmrc.leakdetection.controllers

import java.time.{Duration => JDuration}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.digest.{HmacAlgorithms, HmacUtils}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Filters, Sorts}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterEach, GivenWhenThen, TestData}
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.CONTENT_TYPE
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.leakdetection.{GithubStub, ModelFactory}
import uk.gov.hmrc.leakdetection.GithubStub.TestZippedFile
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, ReportsRepository}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.MongoSupport

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class E2eTests
    extends AnyFeatureSpec
    with GivenWhenThen
    with Matchers
    with GuiceOneAppPerTest
    with MongoSupport
    with ScalaFutures
    with BeforeAndAfterEach
    with Eventually {

  implicit val responseF = Json.format[WebhookResponse]
  implicit val timeout   = Timeout(10.seconds)

  Feature("Verifying Github commits") {
    Scenario("happy path") {
      Given("Github makes a request with all required fields incl. a link to download a zip")
      val githubStub                   = GithubStub.servingZippedFiles(List(TestZippedFile("content", "/foo/bar")))
      val payloadDetails               = aPayloadDetails.copy(archiveUrl = githubStub.archiveUrl, deleted = false)
      val githubRequestPayload: String = asJson(payloadDetails)

      And("the request is signed using a secret known to us")
      val signedRequest =
        FakeRequest("POST", "/validate")
          .withBody(githubRequestPayload)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature-256" -> ("sha256=" + generateHmac(githubRequestPayload))
          )

      When("Leak Detection service receives a request")
      val res = Helpers.route(app, signedRequest).get

      Then("Processing should be successful")
      Helpers.status(res) shouldBe 200

      And("Result should notify that the request has been queued")
      val report = Json.parse(Helpers.contentAsString(res)).as[WebhookResponse]
      report.details shouldBe "Request successfully queued"

      And("New report will eventually be created after branch is processed")
      eventually {
        val reports = findReportsForRepoAndBranch(payloadDetails.repositoryName, payloadDetails.branchRef).futureValue
        reports.size shouldBe 1
      }
    }

    Scenario("tags") {

      Given("Github makes a request where the ref indicates a tag")
      val githubRequestPayload: String =
        asJson(aPayloadDetails.copy(deleted = false, branchRef = "refs/tags/v6.17.0"))

      And("the request is signed using a secret known to us")
      val signedRequest =
        FakeRequest("POST", "/validate")
          .withBody(githubRequestPayload)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature-256" -> ("sha256=" + generateHmac(githubRequestPayload))
          )

      When("Leak Detection service receives a request")
      val res = Helpers.route(app, signedRequest).get

      Then("Processing should be skipped and return 200")
      Helpers.status(res) shouldBe 200

      And("Report should include info about why the request was skipped")
      val details = Json.parse(Helpers.contentAsString(res)) \ "details"
      details.as[String] shouldBe "Tag commit ignored"
    }

    Scenario("Delete branch event") {
      Given("Github makes a request representing a delete branch event")
      val requestPayload               = aPayloadDetails.copy(deleted = true)
      val githubRequestPayload: String = asJson(requestPayload)

      And("the request is signed using a secret known to us")
      val signedRequest =
        FakeRequest("POST", "/validate")
          .withBody(githubRequestPayload)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature-256" -> ("sha256=" + generateHmac(githubRequestPayload))
          )

      And("there is already a report with problems for a given repo/branch")
      prepopulateReportWithProblems(requestPayload.repositoryName, requestPayload.branchRef).futureValue

      When("LDS receives a request related to deleting a branch")
      val res = Helpers.route(app, signedRequest).get

      Then("processing should be successful")
      Helpers.status(res) shouldBe 200

      And("response should inform which problem where cleared as a result of deleting a branch")
      val response = Json.parse(Helpers.contentAsString(res)).as[WebhookResponse]
      response.details shouldBe s"${requestPayload.repositoryName}/${requestPayload.branchRef} deleted"
    }

    Scenario("Processing a branch that no longer exists") {
      Given("Github will return 404 when an attempt is made to download a zip")
      val githubStub = GithubStub.serving404

      And("it will provide a url to a no longer existing branch")
      val noLongerExistingArchiveUrl   = githubStub.archiveUrl
      val payloadDetails               = aPayloadDetails.copy(archiveUrl = noLongerExistingArchiveUrl, deleted = false)
      val githubRequestPayload: String = asJson(payloadDetails)

      And("there is already a report with problems for a given repo/branch")
      prepopulateReportWithProblems(payloadDetails.repositoryName, payloadDetails.branchRef).futureValue

      When("Github calls LDS")
      val signedRequest =
        FakeRequest("POST", "/validate")
          .withBody(githubRequestPayload)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature-256" -> ("sha256=" + generateHmac(githubRequestPayload))
          )

      val res = Helpers.route(app, signedRequest).get

      Then("Processing should be successful")
      Helpers.status(res) shouldBe 200

      And("Result should notify that the request has been queued")
      val report = Json.parse(Helpers.contentAsString(res)).as[WebhookResponse]
      report.details shouldBe "Request successfully queued"

      And("Eventually previous reports with problems will be cleared")
      eventually {
        val report = findReportsForRepoAndBranch(payloadDetails.repositoryName, payloadDetails.branchRef).futureValue.head
        report.totalLeaks shouldBe 0
        report.rulesViolated shouldBe Map.empty
      }
    }
  }

  val queueRepo = new GithubRequestsQueueRepository(Configuration(), mongoComponent) {
    override val inProgressRetryAfter: JDuration = JDuration.ofHours(1)
    override lazy val retryIntervalMillis: Long      = 10000L
  }

  override def afterEach(): Unit = {
    super.afterEach()
    queueRepo.collection.deleteMany(BsonDocument()).toFuture.futureValue
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  val secret: String = aString()
  private def generateHmac(payload: String) = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secret).hmacHex(payload)

  override def newAppForTest(testData: TestData): Application =
    new GuiceApplicationBuilder()
      .configure(
        Configuration(
          ConfigFactory.parseString(
            s"""
              githubSecrets.webhookSecretKey = $secret
              alerts.slack.enabled = false

              scheduling.scanner {
                enabled      = true
                initialDelay = 5 millis
                interval = 1 seconds
              }
              metrics.jvm = false
            """
          ))
      )
      .overrides(bind[MongoComponent].toInstance(mongoComponent))
      .build

  lazy val reportsRepo = new ReportsRepository(mongoComponent)

  def findReportsForRepoAndBranch(repositoryName: String, branchName: String): Future[Seq[Report]] =
    reportsRepo
      .collection
      .find(
        filter = Filters.and(
                   Filters.equal("repoName", repositoryName),
                   Filters.equal("branch", branchName)
                 )
      ).sort(Sorts.descending("timestamp")).toFuture

  def prepopulateReportWithProblems(repoName: String, branchName: String): Future[Unit] =
    reportsRepo
      .saveReport(ModelFactory.aReportWithLeaks(repoName).copy(branch = branchName))
      .map(_ => ())
}
