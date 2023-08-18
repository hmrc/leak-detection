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

import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.ModelFactory
import uk.gov.hmrc.leakdetection.ModelFactory.aSlackConfig
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors._
import uk.gov.hmrc.leakdetection.model._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class AlertingServiceSpec extends AnyWordSpec with Matchers with ArgumentMatchersSugar with ScalaFutures with MockitoSugar {

  implicit val hc = HeaderCarrier()

  "The alerting service" should {
    "send alerts to both alert channel and github repository channel if leaks are in the report" in new Fixtures {

      val report = Report(
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

      val messageDetails = MessageDetails(
        text        = "Do not panic, but there is a leak!",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq(Attachment(s"https://somewhere/leak-detection/repositories/repo-name/${report.branch}?source=slack-lds")),
        showAttachmentAuthor = false
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      val expectedMessageToGithubRepositoryChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.GithubRepository(report.repoName),
        messageDetails = messageDetails
      )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-alert-channel"))))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-team-channel"))))

      service.alert(report).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(any)


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
        rulesViolated = Map(RuleId("no nulls allowed") -> 1),
        exclusions = Map.empty,
        unusedExemptions = Seq.empty
      )


      private val messageDetails: MessageDetails = MessageDetails(
        text = "Do not panic, but there is a leak!",
        username = "leak-detection",
        iconEmoji = ":closed_lock_with_key:",
        attachments = Seq(Attachment("https://somewhere/leak-detection/repositories/repo-name/branch%2Fthat%2Fneeds%2Fencoding?source=slack-lds")),
        showAttachmentAuthor = false
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      val expectedMessageToGithubRepositoryChannel = SlackNotificationRequest(
        channelLookup = ChannelLookup.GithubRepository(report.repoName),
        messageDetails = messageDetails
      )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-alert-channel"))))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-team-channel"))))


      service.alert(report).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
    }

    "not send leak alerts to slack if not enabled" in new Fixtures {

      override val slackConfig =  aSlackConfig.copy(enabled = false)

      val report = Report(
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

      service.alert(report).futureValue

      verifyZeroInteractions(slackConnector)

    }
    "not send alerts to slack if there are no leaks" in new Fixtures {

      val report = Report(
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

      service.alert(report).futureValue

      verifyZeroInteractions(slackConnector)

    }
    "not send alerts to slack if only excluded leaks" in new Fixtures {

      val report = Report(
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

      service.alert(report).futureValue

      verifyZeroInteractions(slackConnector)

    }
    "handle slack notifications errors" when {
      "the message to the Github repository channel fails then fallback to the Users Team Channel" in new Fixtures {

        val report = Report(
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

        val messageDetails = MessageDetails(
          text = "Do not panic, but there is a leak!",
          username = "leak-detection",
          iconEmoji = ":closed_lock_with_key:",
          attachments = Seq(Attachment(s"https://somewhere/leak-detection/repositories/repo-name/${report.branch}?source=slack-lds")),
          showAttachmentAuthor = false
        )

        val expectedMessageToAlertChannel = SlackNotificationRequest(
          channelLookup = ChannelLookup.SlackChannel(slackChannels = List("#the-channel")),
          messageDetails = messageDetails
        )

        val expectedMessageToGithubRepositoryChannel = SlackNotificationRequest(
          channelLookup = ChannelLookup.GithubRepository(report.repoName),
          messageDetails = messageDetails
        )

        val expectedMessageToTeamChannel = SlackNotificationRequest(
          channelLookup = ChannelLookup.TeamsOfGithubUser("me"),
          messageDetails = messageDetails
        )

        when(slackConnector.sendMessage(expectedMessageToAlertChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("#the-channel"))))

        when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel)(hc))
          .thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq.empty, errors = List(SlackNotificationError("slack_channel_not_found", message = "")))))

        when(slackConnector.sendMessage(expectedMessageToTeamChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-team-channel"))))

        service.alert(report).futureValue

        val expectedNumberOfMessages = 3 // 1 for alert channel, 1 for the Github team channel, 1 for team channel
        verify(slackConnector, times(expectedNumberOfMessages)).sendMessage(any)(any)
        reset(slackConnector)
      }
    }

    "not send warning alert if not enabled" in new Fixtures {
      override val slackConfig =  aSlackConfig.copy(enabled = false, warningsToAlert = Seq(InvalidEntry.toString))

      val author = "me"
      service.alertAboutWarnings(author = author, Seq(Warning("a-repo", "a-branch", Instant.now(), ReportId("reportId"), InvalidEntry.toString))).futureValue

      verifyZeroInteractions(slackConnector)
    }

    "send a warning alert if warning in warnings alert list" in new Fixtures {
      override val slackConfig =  aSlackConfig.copy(warningsToAlert = Seq(InvalidEntry.toString))

      val author = "me"


      val messageDetails = MessageDetails(
        text        = "Warning for a-repo with message - invalid entry message",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq(),
        showAttachmentAuthor = false
      )

      val expectedMessageToGithubRepositoryChannel = SlackNotificationRequest(
        channelLookup = ChannelLookup.GithubRepository("a-repo"),
        messageDetails = messageDetails
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-alert-channel"))))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-team-channel"))))

      service.alertAboutWarnings(author = author, Seq(Warning("a-repo", "a-branch", Instant.now(), ReportId("reportId"), InvalidEntry.toString))).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(any)
    }

    "not send warning alerts if not in warning alert list" in new Fixtures {
      service.alertAboutWarnings("author-doesnt-matter", Seq(Warning("repo-doesnt-matter", "branch-doesnt-matter", Instant.now(), ReportId("reportId"), UnusedExemptions.toString))).futureValue

      verifyZeroInteractions(slackConnector)
    }

    "send an alert with attachments if file level exemption warnings" in new Fixtures {
      override val slackConfig =  aSlackConfig.copy(warningsToAlert = Seq(FileLevelExemptions.toString))


      val messageDetails = MessageDetails(
        text        = "Warning for repo with message - file level exemptions",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq(Attachment("https://somewhere/leak-detection/repositories/repo/main/exemptions?source=slack-lds")),
        showAttachmentAuthor = false
      )

      val expectedMessageToGithubRepositoryChannel = SlackNotificationRequest(
        channelLookup = ChannelLookup.GithubRepository("repo"),
        messageDetails = messageDetails
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )
      when(slackConnector.sendMessage(expectedMessageToAlertChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-alert-channel"))))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-team-channel"))))


      service.alertAboutWarnings("author", Seq(Warning("repo", "main", Instant.now, ReportId("reportId"), FileLevelExemptions.toString))).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(any)
    }

    "handle missing warning text with generic message" in new Fixtures {
      override val slackConfig =  aSlackConfig.copy(warningsToAlert = Seq(MissingEntry.toString))

      val author = "me"

      val messageDetails = MessageDetails(
        text        = "Warning for a-repo with message - MissingEntry",
        username    = "leak-detection",
        iconEmoji   = ":closed_lock_with_key:",
        attachments = Seq(),
        showAttachmentAuthor = false
      )

      val expectedMessageToGithubRepositoryChannel = SlackNotificationRequest(
        channelLookup = ChannelLookup.GithubRepository("a-repo"),
        messageDetails = messageDetails
      )

      val expectedMessageToAlertChannel = SlackNotificationRequest(
        channelLookup  = ChannelLookup.SlackChannel(List("#the-channel")),
        messageDetails = messageDetails
      )

      when(slackConnector.sendMessage(expectedMessageToAlertChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("#the-channel"))))
      when(slackConnector.sendMessage(expectedMessageToGithubRepositoryChannel)(hc)).thenReturn(Future.successful(SlackNotificationResponse(successfullySentTo = Seq("some-team-channel"))))

      service.alertAboutWarnings(author = author, Seq(Warning("a-repo", "a-branch", Instant.now(), ReportId("reportId"), MissingEntry.toString))).futureValue

      verify(slackConnector).sendMessage(eqTo(expectedMessageToAlertChannel))(any)
      verify(slackConnector).sendMessage(eqTo(expectedMessageToGithubRepositoryChannel))(any)
    }
  }

  trait Fixtures {

    val slackConnector = mock[SlackNotificationsConnector]


    val slackConfig = aSlackConfig

    lazy val appConfig =
      AppConfig(
        allRules                  = AllRules(Nil, Nil),
        githubSecrets             = GithubSecrets("token"),
        maxLineLength             = Int.MaxValue,
        clearingCollectionEnabled = false,
        warningMessages           = Map("InvalidEntry" -> "invalid entry message", "FileLevelExemptions" -> "file level exemptions"),
        alerts                    = Alerts(slackConfig)
      )

    lazy val service = new AlertingService(appConfig, slackConnector)(ExecutionContext.global)
  }
}
