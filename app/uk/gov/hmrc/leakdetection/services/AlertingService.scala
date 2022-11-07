/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.connectors._
import uk.gov.hmrc.leakdetection.model._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertingService @Inject()(
  configLoader: ConfigLoader,
  slackConnector: SlackNotificationsConnector
)(implicit
  ec: ExecutionContext
) {
  import configLoader.cfg.alerts.slack._
  import configLoader.cfg.warningMessages

  private val logger = Logger(getClass)

  def alertAboutWarnings(author: String, warnings: Seq[Warning])(implicit hc: HeaderCarrier): Future[Unit] = {
    if (enabled) {
      warnings.map(warning =>
        if (warningsToAlert.contains(warning.warningMessageType)) {
          val warningMessage = warningMessages.getOrElse(warning.warningMessageType, warning.warningMessageType)
          val attachments =
            if (warning.warningMessageType != FileLevelExemptions.toString)
              Seq.empty
            else
              Seq(Attachment(url"$leakDetectionUri/leak-detection/repositories/${warning.repoName}/${warning.branch}/exemptions".toString))

          val messageDetails =
            MessageDetails(
              text                 = warningText
                                       .replace("{repo}", warning.repoName)
                                       .replace("{warningMessage}", warningMessage),
              username             = username,
              iconEmoji            = iconEmoji,
              attachments          = attachments,
              showAttachmentAuthor = false
            )

          val commitInfo = CommitInfo(author, Branch(warning.branch), Repository(warning.repoName))

          Future
            .traverse(prepareSlackNotifications(messageDetails, commitInfo))(sendSlackMessage)
            .map(_ => ())
        }
      )
    }
    // TODO do we really want to suppress errors with sendSlackMessage? We don't for `alert(Report)`
    Future.unit
  }

  def alert(report: Report)(implicit hc: HeaderCarrier): Future[Unit] =
    if (!enabled || report.rulesViolated.isEmpty) {
      Future.unit
    } else {

      val alertMessage =
        messageText
          .replace("{repo}", report.repoName)
          .replace("{branch}", report.branch)

      val messageDetails =
        MessageDetails(
          text        = alertMessage,
          username    = username,
          iconEmoji   = iconEmoji,
          attachments = Seq(Attachment(url"$leakDetectionUri/leak-detection/repositories/${report.repoName}/${report.branch}".toString)),
          showAttachmentAuthor = false)

      Future
        .traverse(prepareSlackNotifications(messageDetails, CommitInfo.fromReport(report)))(sendSlackMessage)
        .map(_ => ())
    }

  private def prepareSlackNotifications(
    messageDetails: MessageDetails,
    commitInfo: CommitInfo): Seq[SlackNotificationAndErrorMessage] = {

    val alertChannelNotification =
      if (sendToAlertChannel) Some(notificationForAlertChannel(messageDetails, commitInfo)) else None

    val teamChannelNotification =
      if (sendToTeamChannels) Some(notificationForTeam(messageDetails, commitInfo)) else None

    List(alertChannelNotification, teamChannelNotification).flatten

  }

  private def notificationForTeam(messageDetails: MessageDetails, commitInfo: CommitInfo) = {
    val author = commitInfo.author
    val request =
      SlackNotificationRequest(
        channelLookup = ChannelLookup.TeamsOfGithubUser(author),
        messageDetails = messageDetails
      )

    SlackNotificationAndErrorMessage(
      request    = request,
      errorMsg   = s"Error sending message to team channels of user: '$author'",
      commitInfo = commitInfo)
  }

  private def notificationForAlertChannel(messageDetails: MessageDetails, commitInfo: CommitInfo) = {
    val request =
      SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(slackChannels = List(defaultAlertChannel)),
        messageDetails = messageDetails
      )

    SlackNotificationAndErrorMessage(
      request    = request,
      errorMsg   = s"Error sending message to default alert channel: '$defaultAlertChannel'",
      commitInfo = commitInfo
    )
  }

  private def sendSlackMessage(slackNotificationAndErrorMessage: SlackNotificationAndErrorMessage)(
    implicit hc: HeaderCarrier): Future[Unit] =
    slackConnector.sendMessage(slackNotificationAndErrorMessage.request).map {
      case response if response.hasSentMessages => ()
      case response =>
        logger.error(s"Errors sending notification: ${response.errors.mkString("[", ",", "]")}")
        alertAdminsIfNoSlackChannelFound(response.errors, slackNotificationAndErrorMessage.commitInfo)
    }

  private def alertAdminsIfNoSlackChannelFound(errors: List[SlackNotificationError], commitInfo: CommitInfo)(
    implicit hc: HeaderCarrier): Future[Unit] = {
    val errorsToAlert = errors.filterNot { error =>
      error.code == "repository_not_found" || error.code == "slack_error"
    }
    if (errorsToAlert.nonEmpty) {
      slackConnector
        .sendMessage(
          SlackNotificationRequest(
            channelLookup = ChannelLookup.SlackChannel(List(adminChannel)),
            messageDetails = MessageDetails(
              text        = "LDS failed to deliver slack message to intended channels. Errors are shown below:",
              username    = username,
              iconEmoji   = iconEmoji,
              attachments = errorsToAlert.map(e => Attachment(e.message)) :+ commitInfo.toAttachment,
              showAttachmentAuthor = false
            )
          )
        )
        .map(_ => ())
    } else {
      Future.unit
    }
  }
}

final case class SlackNotificationAndErrorMessage(
  request   : SlackNotificationRequest,
  errorMsg  : String,
  commitInfo: CommitInfo
)

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
