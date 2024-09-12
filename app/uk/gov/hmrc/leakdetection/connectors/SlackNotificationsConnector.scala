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
import play.api.{Configuration, Logging}
import play.api.libs.json.*
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import play.api.libs.ws.writeableOf_JsValue

import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackNotificationsConnector @Inject()(
  httpClientV2  : HttpClientV2,
  configuration : Configuration,
  servicesConfig: ServicesConfig,
)(using ExecutionContext) extends Logging:
  import HttpReads.Implicits._


  val url: String = servicesConfig.baseUrl("slack-notifications")

  private val authToken =
    configuration.get[String]("internal-auth.token")

  def sendMessage(message: SlackNotificationsConnector.Message)(using HeaderCarrier): Future[SlackNotificationsConnector.SlackNotificationResponse] =
    given Reads[SlackNotificationsConnector.SlackNotificationResponse] =
      SlackNotificationsConnector.SlackNotificationResponse.reads
    httpClientV2
      .post(url"$url/slack-notifications/v2/notification")
      .setHeader("Authorization" -> authToken)
      .withBody(Json.toJson(message)(SlackNotificationsConnector.Message.writes))
      .execute[SlackNotificationsConnector.SlackNotificationResponse]
      .recoverWith:
        case NonFatal(ex) =>
          logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)

object SlackNotificationsConnector:
  import play.api.libs.functional.syntax._

  case class Message(
    displayName  : String,
    emoji        : String,
    text         : String,
    blocks       : Seq[JsObject],
    channelLookup: ChannelLookup
  )

  object Message:
    val writes: Writes[Message] =
      ( (__ \ "displayName"  ).write[String]
      ~ (__ \ "emoji"        ).write[String]
      ~ (__ \ "text"         ).write[String]
      ~ (__ \ "blocks"       ).write[Seq[JsObject]]
      ~ (__ \ "channelLookup").write[ChannelLookup]
      )(m => Tuple.fromProductTyped(m))

    def toBlocks(mrkdwn: String): Seq[JsObject] =
      Json.obj(
        "type" -> JsString("section")
      , "text" -> Json.obj("type" -> JsString("mrkdwn"), "text" -> JsString(mrkdwn))
      ) :: Nil

  enum ChannelLookup(val by: String):
    case TeamsOfGithubUser(githubUsername: String) extends ChannelLookup("teams-of-github-user")
    case GithubRepository(repositoryName: String ) extends ChannelLookup("github-repository"   )
    case SlackChannel(slackChannels: List[String]) extends ChannelLookup("slack-channel"       )

  object ChannelLookup:
    given Writes[ChannelLookup] =
      Writes:
        case s: SlackChannel      => Json.obj("slackChannels"  -> s.slackChannels,  "by" -> s.by)
        case s: TeamsOfGithubUser => Json.obj("githubUsername" -> s.githubUsername, "by" -> s.by)
        case s: GithubRepository  => Json.obj("repositoryName" -> s.repositoryName, "by" -> s.by)

  case class SlackNotificationError(
    code: String,
    message: String
  )

  case class SlackNotificationResponse(
    errors: List[SlackNotificationError]
  )

  object SlackNotificationResponse:
    val reads: Reads[SlackNotificationResponse] =
      given Reads[SlackNotificationError] =
        ( (__ \ "code"   ).read[String]
        ~ (__ \ "message").read[String]
        )(SlackNotificationError.apply _)

      (__ \ "errors")
        .readWithDefault[List[SlackNotificationError]](List.empty)
        .map(SlackNotificationResponse.apply)
