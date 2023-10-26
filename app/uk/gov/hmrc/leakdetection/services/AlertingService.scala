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

import cats.implicits._
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

  def alertAboutWarnings(author: String, warnings: Seq[Warning], isPrivate: Boolean)(implicit hc: HeaderCarrier): Future[Unit] =
    warnings
      .filter(warning => slackConfig.enabled && slackConfig.warningsToAlert.contains(warning.warningMessageType))
      .traverse_ { warning =>
        val msg = slackConfig.warningText
                             .replace("{repo}", warning.repoName)
                             .replace("{warningMessage}", appConfig.warningMessages.getOrElse(warning.warningMessageType, warning.warningMessageType))
                             .replace("{repoVisibility}", RepoVisibility.repoVisibility(isPrivate))

        processSlackChannelMessages(
          displayName = slackConfig.username
        , emoji       = slackConfig.iconEmoji
        , text        = msg
        , blocks      = SlackNotificationsConnector.Message.toBlocks(
                          message      = msg
                        , referenceUrl = Option
                                          .when(warning.warningMessageType == FileLevelExemptions.toString)(
                                            (url"${slackConfig.leakDetectionUri}/leak-detection/repositories/${warning.repoName}/${warning.branch}/exemptions?source=slack-lds", "View in Catalogue")
                                          )
                        )
        , commitInfo  = CommitInfo(author, Branch(warning.branch), Repository(warning.repoName))
        )
      }

  def alert(report: Report, isPrivate: Boolean)(implicit hc: HeaderCarrier): Future[Unit] =
    if (!slackConfig.enabled || report.rulesViolated.isEmpty)
      Future.unit
    else {
      val msg = slackConfig.messageText
                           .replace("{repo}", report.repoName)
                           .replace("{branch}", report.branch)
                           .replace("{repoVisibility}", RepoVisibility.repoVisibility(isPrivate))

      processSlackChannelMessages(
        displayName = slackConfig.username
      , emoji       = slackConfig.iconEmoji
      , text        = msg
      , blocks      = SlackNotificationsConnector.Message.toBlocks(
                        message      = msg
                      , referenceUrl = Some((url"${slackConfig.leakDetectionUri}/leak-detection/repositories/${report.repoName}/${report.branch}?source=slack-lds", "View in Catalogue"))
                      )
      , commitInfo  = CommitInfo.fromReport(report)
      )
    }

  private def processSlackChannelMessages(displayName: String, emoji: String, text: String, blocks: Seq[play.api.libs.json.JsObject], commitInfo: CommitInfo)(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      slackAlertMessage       <- Future.successful(SlackNotificationsConnector.Message(
                                   displayName   = displayName
                                 , emoji         = emoji
                                 , text          = text
                                 , blocks        = blocks
                                 , channelLookup = SlackNotificationsConnector.ChannelLookup.SlackChannel(slackChannels = List(slackConfig.defaultAlertChannel))
                                 ))
      _                       <- sendSlackMessage(slackConfig.alertChannelEnabled, slackAlertMessage)
      sentToRepositoryChannel <- sendSlackMessage(slackConfig.repositoryChannelEnabled, slackAlertMessage.copy(channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository(commitInfo.repository.asString)))
      _                       <- if (sentToRepositoryChannel)
                                   Future.unit
                                 else {
                                   logger.warn("Failed to notify the Github Team falling back to notifying the User's Team")
                                   sendSlackMessage(slackConfig.repositoryChannelEnabled, slackAlertMessage.copy(channelLookup = SlackNotificationsConnector.ChannelLookup.TeamsOfGithubUser(commitInfo.author)))
                                 }
    } yield ()

  private def sendSlackMessage(enabled: Boolean, slackMessage: SlackNotificationsConnector.Message)(implicit hc: HeaderCarrier): Future[Boolean] =
    if (enabled)
      slackConnector.sendMessage(slackMessage).map {
        case rsp if rsp.hasSentMessages => true
        case rsp                        => logger.error(s"Errors sending notification: ${rsp.errors.mkString("[", ",", "]")}")
                                           false
      }
    else {
      logger.info(s"Slack notifications disabled for ${slackMessage.channelLookup.by}")
      Future.successful(true)
    }
}

final case class CommitInfo(
  author    : String,
  branch    : Branch,
  repository: Repository
)

object CommitInfo {
  def fromReport(report: Report): CommitInfo =
    CommitInfo(
      author     = report.author,
      branch     = Branch(report.branch),
      repository = Repository(report.repoName)
    )
}

object RepoVisibility {
  def repoVisibility(isPrivate: Boolean): String =
    if (isPrivate) "Private" else ":alert: `Public` :alert:"
}
