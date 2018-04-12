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

package uk.gov.hmrc.leakdetection.connectors

import com.google.common.io.BaseEncoding
import javax.inject.{Inject, Singleton}
import play.api.Mode.Mode
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class SlackNotificationsConnector @Inject()(
  http: HttpClient,
  override val runModeConfiguration: Configuration,
  environment: Environment)
    extends ServicesConfig {

  val mode: Mode  = environment.mode
  val url: String = baseUrl("slack-notifications")

  private val authorizationHeaderValue = {
    val username = {
      val key = "alerts.slack.basicAuth.username"
      runModeConfiguration
        .getString(key)
        .getOrElse(throw new RuntimeException(s"$key not found in configuration"))
    }

    val password = {
      val key = "alerts.slack.basicAuth.password"
      runModeConfiguration
        .getString(key)
        .getOrElse(throw new RuntimeException(s"$key not found in configuration"))
    }

    s"Basic ${BaseEncoding.base64().encode(s"$username:$password".getBytes("UTF-8"))}"
  }

  def sendMessage(message: SlackNotificationRequest)(implicit hc: HeaderCarrier): Future[SlackNotificationResponse] =
    http
      .POST[SlackNotificationRequest, SlackNotificationResponse](s"$url/slack-notifications/notification", message)(
        implicitly,
        implicitly,
        hc.copy(authorization = Some(Authorization(authorizationHeaderValue))),
        implicitly
      )
      .recoverWith {
        case NonFatal(ex) =>
          Logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)
      }

}

final case class SlackNotificationError(
  code: String,
  message: String
)

object SlackNotificationError {
  implicit val format: OFormat[SlackNotificationError] =
    Json.format[SlackNotificationError]
}

final case class SlackNotificationResponse(
  errors: List[SlackNotificationError]
)

object SlackNotificationResponse {

  implicit val format: OFormat[SlackNotificationResponse] =
    Json.format[SlackNotificationResponse]
}

sealed trait ChannelLookup {
  def by: String
}

object ChannelLookup {
  final case class TeamsOfGithubUser(
    githubUsername: String,
    by: String = "teams-of-github-user"
  ) extends ChannelLookup

  final case class SlackChannel(
    slackChannels: List[String],
    by: String = "slack-channel"
  ) extends ChannelLookup

  implicit val writes: Writes[ChannelLookup] = Writes {
    case s: SlackChannel      => Json.toJson(s)(Json.writes[SlackChannel])
    case s: TeamsOfGithubUser => Json.toJson(s)(Json.writes[TeamsOfGithubUser])
  }
}

final case class Attachment(text: String, fields: Seq[Attachment.Field] = Nil)

object Attachment {
  final case class Field(
    title: String,
    value: String,
    short: Boolean
  )

  object Field {
    implicit val format: OFormat[Field] = Json.format[Field]
  }

  implicit val format: OFormat[Attachment] = Json.format[Attachment]

}

final case class MessageDetails(
  text: String,
  username: String,
  iconEmoji: String,
  attachments: Seq[Attachment]
)

object MessageDetails {
  implicit val writes: OWrites[MessageDetails] = Json.writes[MessageDetails]
}

final case class SlackNotificationRequest(
  channelLookup: ChannelLookup,
  messageDetails: MessageDetails
)

object SlackNotificationRequest {
  implicit val writes: OWrites[SlackNotificationRequest] = Json.writes[SlackNotificationRequest]
}
