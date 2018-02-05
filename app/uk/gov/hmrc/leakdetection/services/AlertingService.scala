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

package uk.gov.hmrc.leakdetection.services

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import pureconfig.syntax._
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}
import scala.concurrent.Future
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.leakdetection.connectors._
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

@Singleton
class AlertingService @Inject()(configuration: Configuration, slackConnector: SlackNotificationsConnector) {

  private val slackConfig: SlackConfig = {
    implicit def hint[T]: ProductHint[T] =
      ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

    configuration.underlying
      .getConfig("alerts.slack")
      .toOrThrow[SlackConfig]
  }

  import slackConfig._

  def alert(report: Report)(implicit hc: HeaderCarrier): Future[Unit] =
    if (!enabled || report.inspectionResults.isEmpty) {
      Future.successful(())
    } else {
      val slackNotificationRequests = prepareSlackNotifications(report)
      Future.traverse(slackNotificationRequests)(sendSlackMessage).map(_ => ())
    }

  private def prepareSlackNotifications(report: Report): Seq[SlackNotificationRequest] = {
    val alertMessage =
      messageText
        .replace("{repo}", report.repoName)
        .replace("{branch}", report.branch)

    val messageDetails =
      MessageDetails(
        text        = alertMessage,
        username    = username,
        iconEmoji   = iconEmoji,
        attachments = Seq(Attachment(s"$leakDetectionUri/reports/${report._id}")))

    val defaultChannelNotification =
      if (sendToAlertChannel) Some(notificationRequestForSlackChannel(messageDetails)) else None

    val teamChannelNotifications =
      if (sendToTeamChannels) Some(notificationRequestForGitHubRepo(report, messageDetails)) else None

    List(defaultChannelNotification, teamChannelNotifications).flatten

  }

  private def notificationRequestForGitHubRepo(report: Report, messageDetails: MessageDetails) = {
    val slackNotification =
      SlackNotification(
        channelLookup  = ChannelLookup.GithubRepository(report.repoName),
        messageDetails = messageDetails)

    SlackNotificationRequest(
      slackNotification = slackNotification,
      errorMsg          = s"Error sending message to team channels for repository: '${report.repoName}'")
  }

  private def notificationRequestForSlackChannel(messageDetails: MessageDetails) = {
    val slackNotification =
      SlackNotification(
        channelLookup  = ChannelLookup.SlackChannel(slackChannels = List(defaultAlertChannel)),
        messageDetails = messageDetails)

    SlackNotificationRequest(
      slackNotification = slackNotification,
      errorMsg          = s"Error sending message to default alert channel: '$defaultAlertChannel'")
  }

  private def sendSlackMessage(slackNotificationRequest: SlackNotificationRequest)(
    implicit hc: HeaderCarrier): Future[Unit] =
    slackConnector.sendMessage(slackNotificationRequest.slackNotification).map {
      case HttpResponse(200, _, _, _) => ()
      case _                          => Logger.error(slackNotificationRequest.errorMsg)
    }

}

final case class SlackNotificationRequest(
  slackNotification: SlackNotification,
  errorMsg: String
)

final case class SlackConfig(
  enabled: Boolean,
  defaultAlertChannel: String,
  username: String,
  iconEmoji: String,
  sendToAlertChannel: Boolean,
  sendToTeamChannels: Boolean,
  messageText: String,
  leakDetectionUri: String
)
