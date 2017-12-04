/*
 * Copyright 2017 HM Revenue & Customs
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

import ModelFactory._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import concurrent.duration._
import java.io.{BufferedOutputStream, ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.apache.commons.codec.digest.HmacUtils
import org.scalatest.{Matchers, WordSpec}
import org.scalatestplus.play.OneAppPerSuite
import play.api._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{Action, Results}
import play.api.test.Helpers.{CONTENT_DISPOSITION, CONTENT_TYPE}
import play.api.test.{FakeRequest, Helpers}
import play.core.server.{NettyServer, ServerConfig}
import play.api.routing.sird._

class WebhookControllerSpec extends WordSpec with Matchers with OneAppPerSuite with Fixtures {

  "Github Webhook" should {
    "work e2e" in withFakeGithub { archiveUrl =>
      val githubRequest: String =
        asJson(aPayloadDetails.copy(archiveUrl = archiveUrl, isPrivate = true))

      val req =
        FakeRequest("POST", "/leak-detection/validate")
          .withBody(githubRequest)
          .withHeaders(
            CONTENT_TYPE      -> "application/json",
            "X-Hub-Signature" -> ("sha1=" + HmacUtils.hmacSha1Hex(secret, githubRequest))
          )

      implicit val timeout = Timeout(5.seconds)

      val res = Helpers.route(app, req).get

      println("-----------------------")
      println(Helpers.contentAsString(res))
      println("-----------------------")

      Helpers.status(res) shouldBe 200

    }
  }

}

trait Fixtures { self: OneAppPerSuite =>

  implicit val system: ActorSystem    = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  val secret = aString()

  implicit override lazy val app: Application =
    new GuiceApplicationBuilder()
      .configure(
        Configuration(
          ConfigFactory.parseString(
            s"""
              rules {
                publicRules = []
                privateRules = [
                  {
                   regex = "^.*(null).*$$"
                   tag = "uses nulls!"
                  },
                  {
                   regex = "^.*(throw).*$$"
                   tag = "throws exceptions!"
                  }
                ]
              }

              githubSecrets {
                webhookSecretKey = $secret
                personalAccessToken = pat
              }
            """
          ))
      )
      .build

  val server = NettyServer.fromRouter(
    new ServerConfig(
      rootDir    = new java.io.File("."),
      port       = Some(0),
      sslPort    = None,
      address    = "0.0.0.0",
      mode       = play.api.Mode.Test,
      properties = System.getProperties,
      configuration = play.api.Configuration(
        "play.server.netty" -> Map(
          "maxInitialLineLength" -> 4096,
          "maxHeaderSize"        -> 8192,
          "maxChunkSize"         -> 8192,
          "log.wire"             -> false,
          "eventLoopThreads"     -> 0,
          "transport"            -> "jdk",
          "option.child"         -> Map()
        )
      )
    )) {
    case GET =>
      Action {
        Results.Ok
          .chunked(StreamConverters.fromInputStream(createZip))
          .withHeaders(
            CONTENT_TYPE        -> "application/zip",
            CONTENT_DISPOSITION -> s"attachment; filename = test.zip"
          )
      }
  }

  type ArchiveUrl = String

  def withFakeGithub(f: ArchiveUrl => Any): Any =
    try {
      f(s"http://localhost:${server.httpPort.get}")
    } finally {
      server.stop
    }

  var zippedFiles = List.empty[TestZippedFile]

  def createZip(): ByteArrayInputStream = {
    val baos = new ByteArrayOutputStream()
    val zos  = new ZipOutputStream(new BufferedOutputStream(baos))

    try {
      zippedFiles.foreach { file =>
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
