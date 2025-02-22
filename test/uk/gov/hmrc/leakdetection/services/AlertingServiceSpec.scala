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

import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.{reset, times, verify, verifyNoInteractions, when}
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.leakdetection.ModelFactory.aSlackConfig
import uk.gov.hmrc.leakdetection.config.*
import uk.gov.hmrc.leakdetection.connectors.*
import uk.gov.hmrc.leakdetection.model.*
import uk.gov.hmrc.leakdetection.model.WarningMessageType._

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class AlertingServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar:

  given HeaderCarrier = HeaderCarrier()

  "The alerting service" should:
    "send alerts to both alert channel and github repository channel if leaks are in the report" in new Fixtures:
      val report: Report =
        Report(
          id        = ReportId.random,
          repoName  = "repo-name",
          repoUrl   = "https://github.com/hmrc/a-repo",
          commitId  = "123",
          branch    = "main",
          timestamp = Instant.now(),
          author    = "me",
          totalLeaks = 1,
          rulesViolated = Map(RuleId("no nulls allowed") -> 1),
          exclusions = Map.empty,
          unusedExemptions = Seq.empty
        )

      val expectedMessageToAlertChannel: SlackNotificationsConnector.Message =
        SlackNotificationsConnector.Message(
          displayName     = "leak-detection",
          emoji           = ":closed_lock_with_key:",
          text            = "Something sensitive seems to have been pushed for repo: repo-name on branch: main",
          blocks          = SlackNotificationsConnector.Message.toBlocks(s"""Do not panic, but there is a leak! See ${url"https://somewhere/leak-detection/repositories/repo-name/${report.branch}?source=slack-lds"} To resolve see https://somewhere and https://somewhere-else"""),
          channelLookup   = SlackNotificationsConnector.ChannelLookup.SlackChannel(List("#the-channel")),
          callbackChannel = Some("team-platops-alerts")
        )

      val expectedMessageToGithubRepositoryChannel: SlackNotificationsConnector.Message =
        expectedMessageToAlertChannel.copy(
          channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository(report.repoName)
        )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

      service.alert(report, isPrivate = true).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(using any[HeaderCarrier])
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(using any[HeaderCarrier])

    "correctly encode the url for the report attachment" in new Fixtures:
      val report: Report =
        Report(
          id            = ReportId.random,
          repoName      = "repo-name",
          repoUrl       = "https://github.com/hmrc/a-repo",
          commitId      = "123",
          branch        = "branch/that/needs/encoding",
          timestamp     = Instant.now(),
          author        = "me",
          totalLeaks    = 1,
          rulesViolated = Map(RuleId("no nulls allowed") -> 1),
          exclusions = Map.empty,
          unusedExemptions = Seq.empty
        )

      val expectedMessageToAlertChannel: SlackNotificationsConnector.Message =
        SlackNotificationsConnector.Message(
          displayName     = "leak-detection",
          emoji           = ":closed_lock_with_key:",
          text            = "Something sensitive seems to have been pushed for repo: repo-name on branch: branch/that/needs/encoding",
          blocks          = SlackNotificationsConnector.Message.toBlocks(s"""Do not panic, but there is a leak! See ${url"https://somewhere/leak-detection/repositories/repo-name/branch%2Fthat%2Fneeds%2Fencoding?source=slack-lds"} To resolve see https://somewhere and https://somewhere-else"""),
          channelLookup   = SlackNotificationsConnector.ChannelLookup.SlackChannel(List("#the-channel")),
          callbackChannel = Some("team-platops-alerts")
        )

      val expectedMessageToGithubRepositoryChannel: SlackNotificationsConnector.Message =
        expectedMessageToAlertChannel.copy(
          channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository(report.repoName)
        )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

      service.alert(report, isPrivate = true).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(using any[HeaderCarrier])

    "not send leak alerts to slack if not enabled" in new Fixtures:
      override val slackConfig: SlackConfig =
        aSlackConfig.copy(enabled = false)

      val report: Report =
        Report(
          id        = ReportId.random,
          repoName  = "a-repo",
          repoUrl   = "https://github.com/hmrc/a-repo",
          commitId  = "123",
          branch    = "main",
          timestamp = Instant.now(),
          author    = "me",
          totalLeaks = 1,
          rulesViolated = Map(RuleId("no nulls allowed") -> 1),
          exclusions = Map.empty,
          unusedExemptions = Seq.empty
        )

      service.alert(report, isPrivate = true).futureValue

      verifyNoInteractions(slackConnector)

    "not send alerts to slack if there are no leaks" in new Fixtures:
      val report: Report =
        Report(
          id            = ReportId.random,
          repoName      = "a-repo",
          repoUrl       = "https://github.com/hmrc/a-repo",
          commitId      = "123",
          branch        = "main",
          timestamp     = Instant.now(),
          author        = "me",
          totalLeaks    = 0,
          rulesViolated = Map.empty,
          exclusions = Map.empty,
          unusedExemptions = Seq.empty
        )

      service.alert(report, isPrivate = true).futureValue

      verifyNoInteractions(slackConnector)

    "not send alerts to slack if only excluded leaks" in new Fixtures:
      val report: Report =
        Report(
          id            = ReportId.random,
          repoName      = "a-repo",
          repoUrl       = "https://github.com/hmrc/a-repo",
          commitId      = "123",
          branch        = "main",
          timestamp     = Instant.now(),
          author        = "me",
          totalLeaks    = 1,
          rulesViolated = Map.empty,
          exclusions = Map(RuleId("an excluded leak") -> 1),
          unusedExemptions = Seq.empty
        )

      service.alert(report, isPrivate = true).futureValue

      verifyNoInteractions(slackConnector)

    "handle slack notifications errors" when:
      "the message to the Github repository channel fails then fallback to the Users Team Channel" in new Fixtures:
        val report: Report =
          Report(
            id = ReportId.random,
            repoName = "repo-name",
            repoUrl = "https://github.com/hmrc/a-repo",
            commitId = "123",
            branch = "main",
            timestamp = Instant.now(),
            author = "me",
            totalLeaks = 1,
            rulesViolated = Map(RuleId("no nulls allowed") -> 1),
            exclusions = Map.empty,
            unusedExemptions = Seq.empty
          )

        val expectedMessageToAlertChannel: SlackNotificationsConnector.Message =
          SlackNotificationsConnector.Message(
            displayName     = "leak-detection",
            emoji           = ":closed_lock_with_key:",
            text            = "Something sensitive seems to have been pushed for repo: repo-name on branch: main",
            blocks          = SlackNotificationsConnector.Message.toBlocks(s"""Do not panic, but there is a leak! See ${url"https://somewhere/leak-detection/repositories/repo-name/${report.branch}?source=slack-lds"} To resolve see https://somewhere and https://somewhere-else"""),
            channelLookup   = SlackNotificationsConnector.ChannelLookup.SlackChannel(List("#the-channel")),
            callbackChannel = Some("team-platops-alerts")
          )

        val expectedMessageToGithubRepositoryChannel: SlackNotificationsConnector.Message =
          expectedMessageToAlertChannel.copy(
            channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository(report.repoName)
          )

        val expectedMessageToTeamChannel: SlackNotificationsConnector.Message =
          expectedMessageToAlertChannel.copy(
            channelLookup = SlackNotificationsConnector.ChannelLookup.TeamsOfGithubUser("me")
          )

        when(slackConnector.sendMessage(expectedMessageToAlertChannel))
          .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

        when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel))
          .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(
            errors = List(SlackNotificationsConnector.SlackNotificationError("slack_channel_not_found", ""))
          )))

        when(slackConnector.sendMessage(expectedMessageToTeamChannel))
          .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

        service.alert(report, isPrivate = true).futureValue

        val expectedNumberOfMessages = 3 // 1 for alert channel, 1 for the Github team channel, 1 for team channel
        verify(slackConnector, times(expectedNumberOfMessages)).sendMessage(any)(using any[HeaderCarrier])
        reset(slackConnector)

    "not send warning alert if not enabled" in new Fixtures:
      override val slackConfig: SlackConfig =
        aSlackConfig.copy(enabled = false, warningsToAlert = Seq(InvalidEntry.toString))

      service.alertAboutWarnings(author = "me", Seq(Warning("a-repo", "a-branch", Instant.now(), ReportId("reportId"), InvalidEntry.toString)), isPrivate = true).futureValue

      verifyNoInteractions(slackConnector)

    "send a warning alert if warning in warnings alert list" in new Fixtures:
      override val slackConfig: SlackConfig =
        aSlackConfig.copy(warningsToAlert = Seq(InvalidEntry.toString))

      val expectedMessageToAlertChannel: SlackNotificationsConnector.Message =
        SlackNotificationsConnector.Message(
          displayName     = "leak-detection",
          emoji           = ":closed_lock_with_key:",
          text            = "Leak Detection had a problem scanning repo: a-repo on branch: a-branch",
          blocks          = SlackNotificationsConnector.Message.toBlocks("Warning for a-repo with message - invalid entry message"),
          channelLookup   = SlackNotificationsConnector.ChannelLookup.SlackChannel(List("#the-channel")),
          callbackChannel = Some("team-platops-alerts")
        )

      val expectedMessageToGithubRepositoryChannel: SlackNotificationsConnector.Message =
        expectedMessageToAlertChannel.copy(
          channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository("a-repo")
        )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

      service.alertAboutWarnings(author = "me", Seq(Warning("a-repo", "a-branch", Instant.now(), ReportId("reportId"), InvalidEntry.toString)), isPrivate = true).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(using any[HeaderCarrier])
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(using any[HeaderCarrier])

    "not send warning alerts if not in warning alert list" in new Fixtures:
      service.alertAboutWarnings("author-doesnt-matter", Seq(Warning("repo-doesnt-matter", "branch-doesnt-matter", Instant.now(), ReportId("reportId"), UnusedExemptions.toString)), isPrivate = true).futureValue

      verifyNoInteractions(slackConnector)

    "send an alert with report url if file level exemption warnings" in new Fixtures:
      override val slackConfig: SlackConfig =
        aSlackConfig.copy(warningsToAlert = Seq(FileLevelExemptions.toString))

      val expectedMessageToAlertChannel: SlackNotificationsConnector.Message =
        SlackNotificationsConnector.Message(
          displayName     = "leak-detection",
          emoji           = ":closed_lock_with_key:",
          text            = "Leak Detection had a problem scanning repo: repo on branch: main",
          blocks          = SlackNotificationsConnector.Message.toBlocks(s"""Warning for repo with message - file level exemptions See ${url"https://somewhere/leak-detection/repositories/repo/main/exemptions?source=slack-lds"}"""),
          channelLookup   = SlackNotificationsConnector.ChannelLookup.SlackChannel(List("#the-channel")),
          callbackChannel = Some("team-platops-alerts")
        )

      val expectedMessageToGithubRepositoryChannel: SlackNotificationsConnector.Message =
        expectedMessageToAlertChannel.copy(
          channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository("repo")
        )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

      service.alertAboutWarnings("author", Seq(Warning("repo", "main", Instant.now, ReportId("reportId"), FileLevelExemptions.toString)), isPrivate = true).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(using any[HeaderCarrier])
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(using any[HeaderCarrier])

    "handle missing warning text with generic message" in new Fixtures:
      override val slackConfig: SlackConfig =
        aSlackConfig.copy(warningsToAlert = Seq(MissingEntry.toString))

      val expectedMessageToAlertChannel: SlackNotificationsConnector.Message =
        SlackNotificationsConnector.Message(
          displayName     = "leak-detection",
          emoji           = ":closed_lock_with_key:",
          text            = "Leak Detection had a problem scanning repo: a-repo on branch: a-branch",
          blocks          = SlackNotificationsConnector.Message.toBlocks("Warning for a-repo with message - MissingEntry"),
          channelLookup   = SlackNotificationsConnector.ChannelLookup.SlackChannel(List("#the-channel")),
          callbackChannel = Some("team-platops-alerts")
        )

      val expectedMessageToGithubRepositoryChannel: SlackNotificationsConnector.Message =
        expectedMessageToAlertChannel.copy(
          channelLookup = SlackNotificationsConnector.ChannelLookup.GithubRepository("a-repo")
        )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel))
        .thenReturn(Future.successful(SlackNotificationsConnector.SlackNotificationResponse(errors = Nil)))

      service.alertAboutWarnings(author = "me", Seq(Warning("a-repo", "a-branch", Instant.now(), ReportId("reportId"), MissingEntry.toString)), isPrivate = true).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(using any[HeaderCarrier])
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(using any[HeaderCarrier])

  trait Fixtures:
    val slackConnector: SlackNotificationsConnector =
      mock[SlackNotificationsConnector]

    val slackConfig: SlackConfig =
      aSlackConfig

    lazy val appConfig: AppConfig =
      AppConfig(
        allRules                    = AllRules(Nil, Nil),
        githubSecrets               = GithubSecrets("token"),
        maxLineLength               = Int.MaxValue,
        clearingCollectionEnabled   = false,
        warningMessages             = Map("InvalidEntry" -> "invalid entry message", "FileLevelExemptions" -> "file level exemptions"),
        alerts                      = Alerts(slackConfig),
        timeoutBackoff              = 1.second,
        timeoutBackOffMax           = 1.second,
        timeoutFailureLogAfterCount = 2
      )

    lazy val service: AlertingService =
      AlertingService(appConfig, slackConnector)(using ExecutionContext.global)
