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

import javax.inject.{Inject, Singleton}
import play.api.Mode.Mode
import play.api.libs.json.{Json, OFormat, OWrites, Writes}
import play.api.{Configuration, Environment, Logger}
import scala.concurrent.Future
import scala.util.control.NonFatal
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

@Singleton
class SlackNotificationsConnector @Inject()(
  http: HttpClient,
  override val runModeConfiguration: Configuration,
  environment: Environment)
    extends ServicesConfig {

  val mode: Mode  = environment.mode
  val url: String = baseUrl("slack-notifications")

  def sendMessage(message: SlackNotificationRequest)(implicit hc: HeaderCarrier): Future[SlackNotificationResponse] =
    http
      .POST[SlackNotificationRequest, SlackNotificationResponse](s"$url/slack-notifications/notification", message)
      .recoverWith {
        case NonFatal(ex) =>
          Logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)
      }

}

final case class SlackNotificationResponse(errors: List[String])

object SlackNotificationResponse {
  implicit val format: OFormat[SlackNotificationResponse] = Json.format[SlackNotificationResponse]
}

sealed trait ChannelLookup {
  def by: String
}

object ChannelLookup {

  final case class GithubRepository(
    repositoryName: String,
    by: String = "github-repository"
  ) extends ChannelLookup

  final case class SlackChannel(
    slackChannels: List[String],
    by: String = "slack-channel"
  ) extends ChannelLookup

  implicit val writes: Writes[ChannelLookup] = Writes {
    case g: GithubRepository => Json.toJson(g)(Json.writes[GithubRepository])
    case s: SlackChannel     => Json.toJson(s)(Json.writes[SlackChannel])
  }
}

final case class Attachment(text: String)

object Attachment {
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
