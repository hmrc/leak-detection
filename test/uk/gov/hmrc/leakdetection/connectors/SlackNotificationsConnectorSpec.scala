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

package uk.gov.hmrc.leakdetection.connectors

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global

class SlackNotificationsConnectorSpec
   extends AnyWordSpec
      with Matchers
      with ScalaFutures
      with IntegrationPatience
      with WireMockSupport
      with HttpClientV2Support:

  "Connector" should:
    "use internal auth config token" in:
      val hc               = HeaderCarrier(authorization = None)
      val expectedResponse = SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)

      stubFor(
        post(urlEqualTo("/slack-notifications/v2/notification"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""{ "msgId": "3ae36a2e-43ca-46a7-bba4-72b3fdd4a132" }""")
          )
      )

      val configuration =
        Configuration(
          "internal-auth.token"                            -> "PLACEHOLDER",
          "microservice.services.slack-notifications.host" -> wireMockHost,
          "microservice.services.slack-notifications.port" -> wireMockPort
        )

      val connector: SlackNotificationsConnector =
        SlackNotificationsConnector(
          httpClientV2,
          configuration,
          ServicesConfig(configuration)
        )

      val slackMessage =
        SlackNotificationsConnector.Message(
          displayName   = "username"
        , emoji         = "iconEmoji"
        , text          = "text"
        , blocks        = SlackNotificationsConnector.Message.toBlocks(mrkdwn = "some markdown string")
        , channelLookup = SlackNotificationsConnector.ChannelLookup.SlackChannel(slackChannels = Nil)
        )

      val response = connector.sendMessage(slackMessage)(using hc).futureValue

      response shouldBe expectedResponse

      verify(
        postRequestedFor(urlEqualTo("/slack-notifications/v2/notification"))
          .withRequestBody(equalToJson(
            """{
              "displayName" : "username",
              "emoji" : "iconEmoji",
              "text" : "text",
              "blocks" : [ {
                "type" : "section",
                "text" : {
                  "type" : "mrkdwn",
                  "text" : "some markdown string"
                }
              } ],
              "channelLookup" : {
                "slackChannels" : [ ],
                "by" : "slack-channel"
              }
            }"""
          ))
          .withHeader("Authorization", equalTo("PLACEHOLDER")) // leak-detection:development-only base64 encoded
      )
