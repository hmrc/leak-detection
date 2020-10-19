/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mockito.{ArgumentCaptor, MockitoSugar}
import org.mockito.ArgumentMatchers.{any, eq => is}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.ModelFactory
import uk.gov.hmrc.leakdetection.config.Rule
import uk.gov.hmrc.leakdetection.connectors._
import uk.gov.hmrc.leakdetection.model.{Report, ReportId, ReportLine}
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.time.DateTimeUtils

import scala.collection.JavaConverters._

class AlertingServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  "The alerting service" should {
    "send alerts to both alert channel and team channel if leaks are in the report" in new Fixtures {

      val report = Report(
        _id       = ReportId.random,
        repoName  = "repo-name",
        repoUrl   = "https://github.com/hmrc/a-repo",
        commitId  = "123",
        branch    = "master",
        timestamp = DateTimeUtils.now,
        author    = "me",
        inspectionResults = Seq(
          ReportLine(
            filePath    = "/README.md",
            scope       = Rule.Scope.FILE_CONTENT,
            lineNumber  = 2,
            urlToSource = s"https://github.com/hmrc/repoName/blame/master/README.md#L2",
            ruleId      = Some("no nulls allowed"),
            description = "uses nulls!",
            lineText    = " var x = null",
            matches     = List(Match(9, 13)),
            isTruncated = Some(false)
          )),
        None
      )

      service.alert(report).futureValue

      val messageDetails = MessageDetails(
        text        = "Do not panic, but there is a leak!",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq(Attachment(s"https://somewhere/reports/${report._id}"))
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      val expectedMessageToTeamChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.TeamsOfGithubUser(report.author),
        messageDetails = messageDetails
      )

      verify(slackConnector).sendMessage(is(expectedMessageToAlertChannel))(any())
      verify(slackConnector).sendMessage(is(expectedMessageToTeamChannel))(any())
    }

    "not send leak alerts to slack if not enabled" in new Fixtures {

      override val configuration =
        defaultConfiguration ++ Configuration("alerts.slack.enabled" -> false)

      val report = Report(
        _id       = ReportId.random,
        repoName  = "a-repo",
        repoUrl   = "https://github.com/hmrc/a-repo",
        commitId  = "123",
        branch    = "master",
        timestamp = DateTimeUtils.now,
        author    = "me",
        inspectionResults = Seq(
          ReportLine(
            filePath    = "/README.md",
            scope       = Rule.Scope.FILE_CONTENT,
            lineNumber  = 2,
            urlToSource = s"https://github.com/hmrc/repoName/blame/master/README.md#L2",
            ruleId      = Some("no nulls allowed"),
            description = "uses nulls!",
            lineText    = " var x = null",
            matches     = List(Match(9, 13)),
            isTruncated = Some(false)
          )),
        None
      )

      service.alert(report).futureValue

      verifyZeroInteractions(slackConnector)

    }
    "not send alerts to slack if there is no leaks" in new Fixtures {

      val report = Report(
        _id               = ReportId.random,
        repoName          = "a-repo",
        repoUrl           = "https://github.com/hmrc/a-repo",
        commitId          = "123",
        branch            = "master",
        timestamp         = DateTimeUtils.now,
        author            = "me",
        inspectionResults = Nil,
        None
      )

      service.alert(report).futureValue

      verifyZeroInteractions(slackConnector)

    }

    "send a message to the admin channel if slack notification failed because team was not on slack" in new Fixtures {
      val errorsRequiringAlerting =
        List(
          "teams_not_found_for_github_username",
          "slack_channel_not_found",
          "teams_not_found_for_repository",
          "slack_channel_not_found_for_team_in_ump").map { code =>
          SlackNotificationError(code, message = "")
        }

      errorsRequiringAlerting.foreach { error =>
        val report = ModelFactory.aReportWithLeaks()

        when(slackConnector.sendMessage(any())(any()))
          .thenReturn(Future.successful(SlackNotificationResponse(errors = List(error))))

        service.alert(report).futureValue

        val expectedNumberOfMessages = 4 // 1 for alert channel, 1 for team channel, since both failed 2 further for admin channel
        verify(slackConnector, times(expectedNumberOfMessages)).sendMessage(any())(any())
        reset(slackConnector)
      }

    }

    "include error context details in the slack message for the admin channel" in new Fixtures {
      val errorRequiringAlert = SlackNotificationError("slack_channel_not_found", message = "")

      val report = ModelFactory.aReportWithLeaks()

      when(slackConnector.sendMessage(any())(any()))
        .thenReturn(Future.successful(SlackNotificationResponse(errors = List(errorRequiringAlert))))

      service.alert(report).futureValue

      val slackMessageCaptor = ArgumentCaptor.forClass(classOf[SlackNotificationRequest])

      val expectedNumberOfMessages = 4 // 1 for alert channel, 1 for team channel, since both failed 2 further for admin channel

      verify(slackConnector, times(expectedNumberOfMessages)).sendMessage(slackMessageCaptor.capture())(any())

      val values = slackMessageCaptor.getAllValues.asScala

      assert(
        values.exists(_.messageDetails.attachments.exists(_.fields == List(
          Attachment.Field(title = "author", value     = report.author, short   = true),
          Attachment.Field(title = "branch", value     = report.branch, short   = true),
          Attachment.Field(title = "repository", value = report.repoName, short = true)
        ))))

      reset(slackConnector)

    }

    "send an alert if repoVisibility is not correctly set in repository.yaml" in new Fixtures {
      override val configuration =
        defaultConfiguration ++ Configuration("alerts.slack.enabledForRepoVisibility" -> true)

      val author = "me"
      service.alertAboutRepoVisibility(repoName = "a-repo", author = author).futureValue

      val messageDetails = MessageDetails(
        text        = "Repo visiblity problem detected",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq()
      )

      val expectedMessageToTeamChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.TeamsOfGithubUser(author),
        messageDetails = messageDetails
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      verify(slackConnector).sendMessage(is(expectedMessageToAlertChannel))(any())
      verify(slackConnector).sendMessage(is(expectedMessageToTeamChannel))(any())
    }

    "not send repo visibility alerts if not enabled" in new Fixtures {
      service.alertAboutRepoVisibility("repo-doesnt-matter", "author-doesnt-matter").futureValue

      verifyZeroInteractions(slackConnector)
    }

  }

  trait Fixtures {

    implicit val hc = HeaderCarrier()

    val slackConnector = mock[SlackNotificationsConnector]
    when(slackConnector.sendMessage(any())(any())).thenReturn(Future.successful(SlackNotificationResponse(Nil)))

    val defaultConfiguration = Configuration(
      "alerts.slack.leakDetectionUri"          -> "https://somewhere",
      "alerts.slack.enabled"                   -> true,
      "alerts.slack.defaultAlertChannel"       -> "#the-channel",
      "alerts.slack.adminChannel"              -> "#the-admin-channel",
      "alerts.slack.messageText"               -> "Do not panic, but there is a leak!",
      "alerts.slack.username"                  -> "leak-detection",
      "alerts.slack.iconEmoji"                 -> ":closed_lock_with_key:",
      "alerts.slack.sendToTeamChannels"        -> true,
      "alerts.slack.sendToAlertChannel"        -> true,
      "alerts.slack.repoVisibilityMessageText" -> "Repo visiblity problem detected",
      "alerts.slack.enabledForRepoVisibility"  -> false
    )

    val configuration = defaultConfiguration

    lazy val service = new AlertingService(configuration, slackConnector)(ExecutionContext.global)

  }
}
