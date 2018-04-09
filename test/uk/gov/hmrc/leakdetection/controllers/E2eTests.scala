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

package uk.gov.hmrc.leakdetection.controllers

import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.digest.HmacUtils
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, FeatureSpec, GivenWhenThen, Matchers, TestData}
import org.scalatestplus.play.OneAppPerTest
import play.api._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.CONTENT_TYPE
import play.api.test.{FakeRequest, Helpers}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.leakdetection.GithubStub.TestZippedFile
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, ReportsRepository}
import uk.gov.hmrc.leakdetection.{GithubStub, ModelFactory}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class E2eTests
    extends FeatureSpec
    with GivenWhenThen
    with Matchers
    with OneAppPerTest
    with MongoSpecSupport
    with ScalaFutures
    with BeforeAndAfterEach
    with Eventually {

  implicit val responseF = Json.format[WebhookResponse]
  implicit val timeout   = Timeout(10.seconds)

  feature("Verifying Github commits") {

    scenario("happy path") {
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
            "X-Hub-Signature" -> ("sha1=" + HmacUtils.hmacSha1Hex(secret, githubRequestPayload))
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
        def findReport() = reportsRepo.findAll().futureValue.find(_.repoName == payloadDetails.repositoryName)
        findReport() shouldBe 'defined
      }
    }

    scenario("tags") {

      Given("Github makes a request where the ref indicates a tag")
      val githubRequestPayload: String =
        asJson(aPayloadDetails.copy(deleted = false, branchRef = "refs/tags/v6.17.0"))

      And("the request is signed using a secret known to us")
      val signedRequest =
        FakeRequest("POST", "/validate")
          .withBody(githubRequestPayload)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature" -> ("sha1=" + HmacUtils.hmacSha1Hex(secret, githubRequestPayload))
          )

      When("Leak Detection service receives a request")
      val res = Helpers.route(app, signedRequest).get

      Then("Processing should be skipped and return 200")
      Helpers.status(res) shouldBe 200

      And("Report should include info about why the request was skipped")
      val details = Json.parse(Helpers.contentAsString(res)) \ "details"
      details.as[String] shouldBe "Tag commit ignored"

    }

    scenario("Delete branch event") {
      Given("Github makes a request representing a delete branch event")
      val requestPayload               = aPayloadDetails.copy(deleted = true)
      val githubRequestPayload: String = asJson(requestPayload)

      And("the request is signed using a secret known to us")
      val signedRequest =
        FakeRequest("POST", "/validate")
          .withBody(githubRequestPayload)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature" -> ("sha1=" + HmacUtils.hmacSha1Hex(secret, githubRequestPayload))
          )

      And("there is already a report with problems for a given repo/branch")
      val existingProblems = prepopulateReportWithProblems(requestPayload.repositoryName, requestPayload.branchRef)

      When("LDS receives a request related to deleting a branch")
      val res = Helpers.route(app, signedRequest).get

      Then("processing should be successful")
      Helpers.status(res) shouldBe 200

      And("response should inform which problem where cleared as a result of deleting a branch")
      val response = Json.parse(Helpers.contentAsString(res)).as[WebhookResponse]
      response.details shouldBe "1 report(s) successfully cleared"

    }

  }

  val queueRepo =
    new GithubRequestsQueueRepository(Configuration(), new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    })

  override def afterEach(): Unit = {
    super.afterEach()
    queueRepo.removeAll().futureValue
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  val secret: String = aString()

  override def newAppForTest(testData: TestData): Application =
    new GuiceApplicationBuilder()
      .configure(
        Configuration(
          ConfigFactory.parseString(
            s"""
              githubSecrets.webhookSecretKey = $secret
              alerts.slack.enabled = false

              scheduling.scanner {
                initialDelay = 5 millis
                interval = 2 seconds
              }
            """
          ))
      )
      .overrides(bind[ReactiveMongoComponent].toInstance(new ReactiveMongoComponent {
        override def mongoConnector: MongoConnector = mongoConnectorForTest
      }))
      .build

  lazy val reportsRepo = new ReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

  def prepopulateReportWithProblems(repoName: String, branchName: String): Report = {
    val report = ModelFactory.aReportWithLeaks(repoName).copy(branch = branchName)
    Await.result(reportsRepo.insert(report), 5.seconds)
    report
  }

}
