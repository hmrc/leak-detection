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

package uk.gov.hmrc.leakdetection.services

import play.api.Logger
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors._
import uk.gov.hmrc.leakdetection.model._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertingService @Inject()(
  appConfig     : AppConfig,
  slackConnector: SlackNotificationsConnector
)(implicit
  ec: ExecutionContext
) {
  private val logger = Logger(getClass)

  private val slackConfig = appConfig.alerts.slack

  type ErrorMessage = String

  def alertAboutWarnings(author: String, warnings: Seq[Warning])(implicit hc: HeaderCarrier): Future[Unit] = {
    if (slackConfig.enabled) {
      warnings.map(warning =>
        if (slackConfig.warningsToAlert.contains(warning.warningMessageType)) {
          val warningMessage = appConfig.warningMessages.getOrElse(warning.warningMessageType, warning.warningMessageType)
          val attachments =
            if (warning.warningMessageType != FileLevelExemptions.toString)
              Seq.empty
            else
              Seq(Attachment(url"${slackConfig.leakDetectionUri}/leak-detection/repositories/${warning.repoName}/${warning.branch}/exemptions?source=slack-lds".toString))

          val messageDetails =
            MessageDetails(
              text                 = slackConfig.warningText
                                       .replace("{repo}", warning.repoName)
                                       .replace("{warningMessage}", warningMessage),
              username             = slackConfig.username,
              iconEmoji            = slackConfig.iconEmoji,
              attachments          = attachments,
              showAttachmentAuthor = false
            )

          val commitInfo = CommitInfo(author, Branch(warning.branch), Repository(warning.repoName))
          processSlackChannelMessages(messageDetails, commitInfo)
        }
      )
    }
    Future.unit
  }



  def alert(report: Report)(implicit hc: HeaderCarrier): Future[Unit] =
    if (!slackConfig.enabled || report.rulesViolated.isEmpty) {
      Future.unit
    } else {

      val alertMessage =
        slackConfig.messageText
          .replace("{repo}", report.repoName)
          .replace("{branch}", report.branch)

      val messageDetails =
        MessageDetails(
          text        = alertMessage,
          username    = slackConfig.username,
          iconEmoji   = slackConfig.iconEmoji,
          attachments = Seq(Attachment(url"${slackConfig.leakDetectionUri}/leak-detection/repositories/${report.repoName}/${report.branch}?source=slack-lds".toString)),
          showAttachmentAuthor = false)

      processSlackChannelMessages(messageDetails, CommitInfo.fromReport(report))
    }

  private def processSlackChannelMessages(messageDetails: MessageDetails, commitInfo: CommitInfo)(implicit hc: HeaderCarrier): Future[Unit] = {
   val result = {
    for {
      _ <- sendSlackMessage(slackConfig.sendToAlertChannel, notificationForAlertChannel(messageDetails, commitInfo))
      sentToRepositoryChannel <- sendSlackMessage(slackConfig.sendToRepositoryChannel, notificationForRepository(messageDetails, commitInfo))
    } yield (sentToRepositoryChannel)
   }

    result.map{ success => if(!success) {
      logger.warn("Failed to notify the Github Team falling back to notifying the User's Team")
      sendSlackMessage(slackConfig.sendToRepositoryChannel, notificationForTeam(messageDetails, commitInfo))
     } else {
      Future.successful(())
     }
    }

  }

  private def notificationForRepository(messageDetails: MessageDetails, commitInfo: CommitInfo): SlackNotificationRequest =
    SlackNotificationRequest(
      channelLookup = ChannelLookup.GithubRepository(commitInfo.repository.asString),
      messageDetails = messageDetails
    )
  private def notificationForTeam(messageDetails: MessageDetails, commitInfo: CommitInfo): SlackNotificationRequest =
      SlackNotificationRequest(
        channelLookup = ChannelLookup.TeamsOfGithubUser(commitInfo.author),
        messageDetails = messageDetails
      )

  private def notificationForAlertChannel(messageDetails: MessageDetails, commitInfo: CommitInfo): SlackNotificationRequest =
      SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(slackChannels = List(slackConfig.defaultAlertChannel)),
        messageDetails = messageDetails
      )

  private def sendSlackMessage(enabled: Boolean, slackNotificationRequest: SlackNotificationRequest)(
    implicit hc: HeaderCarrier): Future[Boolean] =
    if (enabled) {
      slackConnector.sendMessage(slackNotificationRequest).map {
        case response if response.hasSentMessages => true
        case response =>
          logger.error(s"Errors sending notification: ${response.errors.mkString("[", ",", "]")}")
          false
      }
    } else {
      logger.info(s"Slack notifications disabled for ${slackNotificationRequest.channelLookup.by}")
      Future.successful(true)
    }
}

final case class CommitInfo(
  author    : String,
  branch    : Branch,
  repository: Repository
) {
  def toAttachment: Attachment =
    Attachment(
      text = "",
      fields = List(
        Attachment.Field(title = "author", value     = author, short     = true),
        Attachment.Field(title = "branch", value     = branch.asString, short     = true),
        Attachment.Field(title = "repository", value = repository.asString, short = true)
      )
    )

  override def toString: String =
    s"author: $author, branch: ${branch.asString}, repository: ${repository.asString}"
}

object CommitInfo {
  def fromReport(report: Report): CommitInfo =
    CommitInfo(
      author     = report.author,
      branch     = Branch(report.branch),
      repository = Repository(report.repoName)
    )
}
