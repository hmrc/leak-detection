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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import java.io.{BufferedOutputStream, ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.apache.commons.codec.digest.HmacUtils
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
import scala.concurrent.duration._
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.TestServer
import uk.gov.hmrc.leakdetection.controllers.WebhookRequestValidator.Message
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

class WebhookControllerSpec
    extends FeatureSpec
    with GivenWhenThen
    with Matchers
    with OneAppPerTest
    with Fixtures
    with MongoSpecSupport {

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

      And("Github, when called will return a zip with source code files")
      filesInTheArchive = List(
        TestZippedFile(content = "package foo \n var x = null"),
        TestZippedFile(content = "Option(1).getOrElse(throw SadnessException)\n\n foo; throw; throw; throw"),
        TestZippedFile(path    = "/repo/id_rsa", content = "doesn't matter")
      )

      When("Leak Detection service receives a request")
      val res = Helpers.route(app, signedRequest).get

      Then("Processing should be successful")
      Helpers.status(res) shouldBe 200

      And("Report should include info about all found problems")
      val report = Json.parse(Helpers.contentAsString(res)).as[Report]
      val expectedTotalMatches = {
        val contentMatchesCount = 3
        val nameMatchesCount    = 1
        contentMatchesCount + nameMatchesCount
      }
      report.inspectionResults.size shouldBe expectedTotalMatches

    }

    scenario("Delete branch event") {
      Given("Github makes a request representing a delete branch event")
      val githubRequestPayload: String = asJson(aPayloadDetails.copy(deleted = true))

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

      And("Response should inform that 'delete branch' events are ignored")
      val msg = Json.parse(Helpers.contentAsString(res)).as[Message]
      msg.details shouldBe "Events related to deleting branches are ignored"

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
               defaultAlertChannel = "#the-channel"
               messageText         = "Do not panic, but there is a leak!"
               username            = "leak-detection"
               iconEmoji           = ":closed_lock_with_key:"
               sendToTeamChannels  = true
               sendToAlertChannel  = true
              }

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

}

case class TestZippedFile(
  content: String,
  path: String = s"repo/${UUID.randomUUID().toString}"
)
