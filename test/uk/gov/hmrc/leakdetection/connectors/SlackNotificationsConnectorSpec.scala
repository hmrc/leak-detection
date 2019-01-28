/*
 * Copyright 2019 HM Revenue & Customs
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

import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlackNotificationsConnectorSpec extends WordSpec with Matchers with MockitoSugar with ScalaFutures {
  "Connector" should {
    "use basic auth" in {
      val httpClient       = mock[HttpClient]
      val authorization    = Authorization("Basic bGVhay1kZXRlY3Rpb246ZGV2ZWxvcG1lbnQtb25seQ==") // leak-detection:development-only base64 encoded
      val hc               = HeaderCarrier(authorization = None)
      val expectedResponse = SlackNotificationResponse(errors = Nil)

      val argumentCaptor = ArgumentCaptor.forClass(classOf[HeaderCarrier])
      when(
        httpClient
          .POST[SlackNotificationRequest, SlackNotificationResponse](any(), any(), any())(
            any(),
            any(),
            argumentCaptor.capture(),
            any()))
        .thenReturn(Future(expectedResponse))

      val configuration =
        Configuration(
          "alerts.slack.basicAuth.username"                -> "leak-detection",
          "alerts.slack.basicAuth.password"                -> "development-only",
          "microservice.services.slack-notifications.host" -> "localhost",
          "microservice.services.slack-notifications.port" -> 80
        )

      val connector = new SlackNotificationsConnector(httpClient, configuration, Environment.simple())

      val slackMessage =
        SlackNotificationRequest(
          channelLookup  = ChannelLookup.SlackChannel(slackChannels = Nil),
          messageDetails = MessageDetails("text", "username", "iconEmoji", Nil)
        )

      val response = connector.sendMessage(slackMessage)(hc).futureValue
      response                              shouldBe expectedResponse
      argumentCaptor.getValue.authorization shouldBe Some(authorization)

    }
  }
}
