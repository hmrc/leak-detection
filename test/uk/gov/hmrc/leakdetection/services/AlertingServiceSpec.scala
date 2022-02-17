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

import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.ModelFactory
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, PlayConfigLoader}
import uk.gov.hmrc.leakdetection.connectors._
import uk.gov.hmrc.leakdetection.model.{Branch, Report, ReportId, Repository}

import java.time.Instant
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class AlertingServiceSpec extends AnyWordSpec with Matchers with ArgumentMatchersSugar with ScalaFutures with MockitoSugar {

  implicit val hc = HeaderCarrier()

  "The alerting service" should {
    "send alerts to both alert channel and team channel if leaks are in the report" in new Fixtures {

      val report = Report(
        id        = ReportId.random,
        repoName  = "repo-name",
        repoUrl   = "https://github.com/hmrc/a-repo",
        commitId  = "123",
        branch    = "main",
        timestamp = Instant.now(),
        author    = "me",
        totalLeaks = 1,
        rulesViolated = Map("no nulls allowed" -> 1)
      )

      service.alert(report).futureValue

      val messageDetails = MessageDetails(
        text        = "Do not panic, but there is a leak!",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq(Attachment(s"https://somewhere/leak-detection/repositories/repo-name/main"))
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      val expectedMessageToTeamChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.TeamsOfGithubUser(report.author),
        messageDetails = messageDetails
      )

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToTeamChannel))(any)
    }

    "correctly encode the url for the report attachment" in new Fixtures {

      val report = Report(
        id            = ReportId.random,
        repoName      = "repo-name",
        repoUrl       = "https://github.com/hmrc/a-repo",
        commitId      = "123",
        branch        = "branch/that/needs/encoding",
        timestamp     = Instant.now(),
        author        = "me",
        totalLeaks    = 1,
        rulesViolated = Map("no nulls allowed" -> 1)
      )

      service.alert(report).futureValue

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = MessageDetails(
          text        = "Do not panic, but there is a leak!",
          username    = "leak-detection",
          iconEmoji   = ":closed_lock_with_key:",
          attachments = Seq(Attachment("https://somewhere/leak-detection/repositories/repo-name/branch%2Fthat%2Fneeds%2Fencoding"))
        )
      )

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
    }

    "not send leak alerts to slack if not enabled" in new Fixtures {

      override val configuration =  Configuration("alerts.slack.enabled" -> false).withFallback(defaultConfiguration)

      val report = Report(
        id        = ReportId.random,
        repoName  = "a-repo",
        repoUrl   = "https://github.com/hmrc/a-repo",
        commitId  = "123",
        branch    = "main",
        timestamp = Instant.now(),
        author    = "me",
        totalLeaks = 1,
        rulesViolated = Map("no nulls allowed" -> 1)
      )

      service.alert(report).futureValue

      verifyZeroInteractions(slackConnector)

    }
    "not send alerts to slack if there is no leaks" in new Fixtures {

      val report = Report(
        id            = ReportId.random,
        repoName      = "a-repo",
        repoUrl       = "https://github.com/hmrc/a-repo",
        commitId      = "123",
        branch        = "main",
        timestamp     = Instant.now(),
        author        = "me",
        totalLeaks    = 0,
        rulesViolated = Map.empty
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

        when(slackConnector.sendMessage(any)(any))
          .thenReturn(Future.successful(SlackNotificationResponse(errors = List(error))))

        service.alert(report).futureValue

        val expectedNumberOfMessages = 4 // 1 for alert channel, 1 for team channel, since both failed 2 further for admin channel
        verify(slackConnector, times(expectedNumberOfMessages)).sendMessage(any)(any)
        reset(slackConnector)
      }

    }

    "include error context details in the slack message for the admin channel" in new Fixtures {
      val errorRequiringAlert = SlackNotificationError("slack_channel_not_found", message = "")

      val report = ModelFactory.aReportWithLeaks()

      when(slackConnector.sendMessage(any)(any))
        .thenReturn(Future.successful(SlackNotificationResponse(errors = List(errorRequiringAlert))))

      service.alert(report).futureValue

      val slackMessageCaptor = ArgumentCaptor.forClass(classOf[SlackNotificationRequest])

      val expectedNumberOfMessages = 4 // 1 for alert channel, 1 for team channel, since both failed 2 further for admin channel

      verify(slackConnector, times(expectedNumberOfMessages)).sendMessage(slackMessageCaptor.capture())(any)

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
        Configuration("alerts.slack.enabledForRepoVisibility" -> true).withFallback(defaultConfiguration)

      val author = "me"
      service.alertAboutRepoVisibility(repository = Repository("a-repo"), author = author).futureValue

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

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToTeamChannel))(any)
    }

    "not send repo visibility alerts if not enabled" in new Fixtures {
      service.alertAboutRepoVisibility(Repository("repo-doesnt-matter"), "author-doesnt-matter").futureValue

      verifyZeroInteractions(slackConnector)
    }

    "send an alert about exemption warnings" in new Fixtures {
      override val configuration =
        Configuration("alerts.slack.enabledForExemptionWarnings" -> true).withFallback(defaultConfiguration)

      service.alertAboutExemptionWarnings(repository = Repository("repo"), Branch("main"), "author").futureValue

      val messageDetails = MessageDetails(
        text        = "Exemption Warnings",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq()
      )

      val expectedMessageToTeamChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.TeamsOfGithubUser("author"),
        messageDetails = messageDetails
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToTeamChannel))(any)
    }

    "do not send exemption warnings alert if not enabled" in new Fixtures {
      service.alertAboutExemptionWarnings(Repository("repo"), Branch("main"), "author").futureValue

      verifyZeroInteractions(slackConnector)
    }
  }

  trait Fixtures {

    val slackConnector = mock[SlackNotificationsConnector]
    when(slackConnector.sendMessage(any)(any)).thenReturn(Future.successful(SlackNotificationResponse(Nil)))

    val githubService = mock[GithubService]
    when(githubService.getDefaultBranchName(Repository(any))(any,any)).thenReturn(Future.successful(Branch.main))


    val defaultConfiguration = Configuration(
      "alerts.slack.leakDetectionUri"             -> "https://somewhere",
      "alerts.slack.enabled"                      -> true,
      "alerts.slack.defaultAlertChannel"          -> "#the-channel",
      "alerts.slack.adminChannel"                 -> "#the-admin-channel",
      "alerts.slack.messageText"                  -> "Do not panic, but there is a leak!",
      "alerts.slack.username"                     -> "leak-detection",
      "alerts.slack.iconEmoji"                    -> ":closed_lock_with_key:",
      "alerts.slack.sendToTeamChannels"           -> true,
      "alerts.slack.sendToAlertChannel"           -> true,
      "alerts.slack.repoVisibilityMessageText"    -> "Repo visiblity problem detected",
      "alerts.slack.enabledForRepoVisibility"     -> false,
      "alerts.slack.exemptionWarningText"         -> "Exemption Warnings",
      "alerts.slack.enabledForExemptionWarnings"  -> false,
      "githubSecrets.personalAccessToken"         -> "PLACEHOLDER",
      "githubSecrets.webhookSecretKey"            -> "PLACEHOLDER",
      "github.url"                                -> "url",
      "github.apiUrl"                             -> "url",
      "allRules.privateRules"                     -> List(),
      "allRules.publicRules"                      -> List(),
      "leakResolutionUrl"                         -> "PLACEHOLDER",
      "maxLineLength"                             -> 2147483647,
      "clearingCollectionEnabled"                 -> false
    )

    val configuration = defaultConfiguration
    val configLoader: ConfigLoader = new PlayConfigLoader(defaultConfiguration)

    lazy val service = new AlertingService(configuration, slackConnector, githubService)(ExecutionContext.global)

  }
}
