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

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.json._
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackNotificationsConnector @Inject()(
  httpClientV2  : HttpClientV2,
  configuration : Configuration,
  servicesConfig: ServicesConfig,
)(implicit ec: ExecutionContext) {
  import HttpReads.Implicits._

  private val logger = Logger(getClass)

  val url: String = servicesConfig.baseUrl("slack-notifications")

  private val authToken =
    configuration.get[String]("alerts.slack.auth-token")

  def sendMessage(message: SlackNotificationsConnector.Message)(implicit hc: HeaderCarrier): Future[SlackNotificationsConnector.MessageResponse] = {
    httpClientV2
      .post(url"$url/slack-notifications/v2/notification")
      .setHeader("Authorization" -> authToken)
      .withBody(Json.toJson(message)(SlackNotificationsConnector.Message.writes))
      .execute[SlackNotificationsConnector.MessageResponse]
      .recoverWith {
        case NonFatal(ex) =>
          logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)
      }
  }
}

object SlackNotificationsConnector {
  import play.api.libs.functional.syntax._

  final case class Message(
    displayName  : String,
    emoji        : String,
    text         : String,
    blocks       : Seq[JsObject],
    channelLookup: ChannelLookup
  )

  object Message {
    val writes: Writes[Message] =
      ( (__ \ "displayName"  ).write[String]
      ~ (__ \ "emoji"        ).write[String]
      ~ (__ \ "text"         ).write[String]
      ~ (__ \ "blocks"       ).write[Seq[JsObject]]
      ~ (__ \ "channelLookup").write[ChannelLookup](ChannelLookup.writes)
      )(unlift(Message.unapply))

  def toBlocks(message: String, referenceUrl: Option[(java.net.URL, String)]): Seq[JsObject] =
    Json.obj(
      "type" -> JsString("section")
    , "text" -> Json.obj("type" -> JsString("mrkdwn"), "text" -> JsString(message))
    ) :: (referenceUrl match {
      case Some((url, title)) =>
        Json.obj("type" -> JsString("divider"))                                      ::
        Json.obj("type" -> JsString("mrkdwn"), "text" -> JsString(s"<$url|$title>")) ::
        Nil
      case None               => Nil
    })
  }

  sealed trait ChannelLookup { def by: String }

  object ChannelLookup {
    final case class TeamsOfGithubUser(
      githubUsername: String,
      by            : String = "teams-of-github-user"
    ) extends ChannelLookup

    final case class GithubRepository(
      repositoryName: String,
      by            : String = "github-repository"
    ) extends ChannelLookup

    final case class SlackChannel(
      slackChannels: List[String],
      by           : String = "slack-channel"
    ) extends ChannelLookup

    implicit val writes: Writes[ChannelLookup] = Writes {
      case s: SlackChannel      => Json.toJson(s)(Json.writes[SlackChannel])
      case s: TeamsOfGithubUser => Json.toJson(s)(Json.writes[TeamsOfGithubUser])
      case s: GithubRepository  => Json.toJson(s)(Json.writes[GithubRepository])
    }
  }

  final case class SlackNotificationError(
    code   : String,
    message: String
  )

  object SlackNotificationError {
    implicit val format: OFormat[SlackNotificationError] =
      Json.format[SlackNotificationError]
  }

  final case class MessageResponse(
    successfullySentTo: Seq[String]                  = Nil,
    errors            : List[SlackNotificationError] = Nil
  ) {
    def hasSentMessages: Boolean = successfullySentTo.nonEmpty
  }

  object MessageResponse {
    implicit val format: OFormat[MessageResponse] =
      Json.format[MessageResponse]
  }
}
