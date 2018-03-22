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

import java.io.{BufferedOutputStream, ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.digest.HmacUtils
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers, TestData}
import org.scalatestplus.play.OneAppPerTest
import play.api._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.{Action, Results}
import play.api.routing.sird._
import play.api.test.Helpers.{CONTENT_DISPOSITION, CONTENT_TYPE}
import play.api.test.{FakeRequest, Helpers}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.leakdetection.{ModelFactory, TestServer}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WebhookControllerSpec
    extends FeatureSpec
    with GivenWhenThen
    with Matchers
    with OneAppPerTest
    with Fixtures
    with MongoSpecSupport
    with ScalaFutures {

  implicit val resposneF = Json.format[WebhookResponse]

  feature("Verifying Github commits") {

    scenario("happy path") {

      Given("Github makes a request with all required fields incl. a link to download a zip")
      And("repository is private")
      val githubRequestPayload: String =
        asJson(aPayloadDetails.copy(archiveUrl = archiveUrl, isPrivate = true, deleted = false))

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
    }

    scenario("tags") {

      Given("Github makes a request where the ref indicates a tag")
      val githubRequestPayload: String =
        asJson(
          aPayloadDetails
            .copy(archiveUrl = archiveUrl, isPrivate = true, deleted = false, branchRef = "refs/tags/v6.17.0"))

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

}

trait Fixtures { self: OneAppPerTest with MongoSpecSupport =>

  implicit val timeout                = Timeout(10.seconds)
  implicit val system: ActorSystem    = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  val secret = aString()

  override def newAppForTest(testData: TestData): Application =
    new GuiceApplicationBuilder()
      .configure(
        Configuration(
          ConfigFactory.parseString(
            s"""
              allRules {
                publicRules = []
                privateRules = [
                  {
                   id = "rule-1"
                   scope = "fileContent"
                   regex = "null"
                   description = "uses nulls!"
                  },
                  {
                   id = "rule-2"
                   scope = "fileContent"
                   regex = "throw"
                   description = "throws exceptions!"
                  },
                  {
                   id = "rule-2"
                   scope = "fileName"
                   regex = ".*_rsa"
                   description = "private key"
                  }
                ]
              }

              githubSecrets {
                webhookSecretKey = $secret
                personalAccessToken = pat
              }

              allRuleExemptions {
                global = []
              }

              alerts.slack {
               leakDetectionUri    = "https://somewhere"
               enabled             = false
               adminChannel        = "#the-admin-channel"
               defaultAlertChannel = "#the-channel"
               messageText         = "Do not panic, but there is a leak!"
               username            = "leak-detection"
               iconEmoji           = ":closed_lock_with_key:"
               sendToTeamChannels  = true
               sendToAlertChannel  = true
              }

              maxLineLength = 2147483647 // Int.MaxValue

            """
          ))
      )
      .overrides(bind[ReactiveMongoComponent].toInstance(new ReactiveMongoComponent {
        override def mongoConnector: MongoConnector = mongoConnectorForTest
      }))
      .build

  val server =
    TestServer {
      case GET(p"/") =>
        Action {
          Results.Ok
            .chunked(StreamConverters.fromInputStream(createZip))
            .withHeaders(
              CONTENT_TYPE        -> "application/zip",
              CONTENT_DISPOSITION -> s"attachment; filename = test.zip"
            )
        }
    }

  val archiveUrl = s"http://localhost:${server.httpPort.get}/"

  var filesInTheArchive: List[TestZippedFile] = _

  def createZip(): ByteArrayInputStream = {
    val baos = new ByteArrayOutputStream()
    val zos  = new ZipOutputStream(new BufferedOutputStream(baos))

    try {
      filesInTheArchive.foreach { file =>
        zos.putNextEntry(new ZipEntry(file.path))
        zos.write(file.content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }

    new ByteArrayInputStream(baos.toByteArray)
  }

  lazy val repo = new ReportsRepository(new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  })

  def prepopulateReportWithProblems(repoName: String, branchName: String): Report = {
    val report = ModelFactory.aReportWithUnresolvedProblems(repoName).copy(branch = branchName)
    Await.result(repo.insert(report), 5.seconds)
    report
  }

}

case class TestZippedFile(
  content: String,
  path: String = s"repo/${UUID.randomUUID().toString}"
)
