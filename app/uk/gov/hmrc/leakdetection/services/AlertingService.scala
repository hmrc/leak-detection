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
import uk.gov.hmrc.http.HeaderCarrier
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
      val slackNotifications = prepareSlackNotifications(report)
      Future.traverse(slackNotifications)(sendSlackMessage).map(_ => ())
    }

  private def prepareSlackNotifications(report: Report): Seq[SlackNotificationAndErrorMessage] = {
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
      if (sendToAlertChannel) Some(notificationForSlackChannel(messageDetails)) else None

    val teamChannelNotifications =
      if (sendToTeamChannels) Some(notificationForGitHubRepo(report, messageDetails)) else None

    List(defaultChannelNotification, teamChannelNotifications).flatten

  }

  private def notificationForGitHubRepo(report: Report, messageDetails: MessageDetails) = {
    val request =
      SlackNotificationRequest(
        channelLookup  = ChannelLookup.GithubRepository(report.repoName),
        messageDetails = messageDetails)

    SlackNotificationAndErrorMessage(
      request  = request,
      errorMsg = s"Error sending message to team channels for repository: '${report.repoName}'")
  }

  private def notificationForSlackChannel(messageDetails: MessageDetails) = {
    val request =
      SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(slackChannels = List(defaultAlertChannel)),
        messageDetails = messageDetails)

    SlackNotificationAndErrorMessage(
      request  = request,
      errorMsg = s"Error sending message to default alert channel: '$defaultAlertChannel'")
  }

  private def sendSlackMessage(slackNotificationAndErrorMessage: SlackNotificationAndErrorMessage)(
    implicit hc: HeaderCarrier): Future[Unit] =
    slackConnector.sendMessage(slackNotificationAndErrorMessage.request).map {
      case SlackNotificationResponse(errors) if errors.isEmpty => ()
      case SlackNotificationResponse(errors) =>
        Logger.error(s"Errors sending notification: ${errors.mkString("[", ",", "]")}")
      case _ => Logger.error(slackNotificationAndErrorMessage.errorMsg)
    }

}

final case class SlackNotificationAndErrorMessage(
  request: SlackNotificationRequest,
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