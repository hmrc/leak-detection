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

import org.mockito.Matchers.{any, eq => is}
import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.leakdetection.config.Rule
import uk.gov.hmrc.leakdetection.connectors.{Attachment, SlackConnector, SlackMessage}
import uk.gov.hmrc.leakdetection.model.{Report, ReportId, ReportLine}
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.Future

class AlertingServiceSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar {

  "The alerting service" should {
    "send an alert to a configurable slack channel if leaks are in the report" in {

      implicit val hc = HeaderCarrier()

      val slackConnector = mock[SlackConnector]
      when(slackConnector.sendMessage(any())(any())).thenReturn(Future.successful(HttpResponse(200)))

      val configuration = Configuration(
        "leakDetection.uri"                     -> "https://somewhere",
        "alerts.slack.defaultAlertChannel.name" -> "#the-channel",
        "alerts.slack.message.text"             -> "Do not panic, but there is a leak!",
        "alerts.slack.user.name"                -> "leak-detection",
        "alerts.slack.user.icon"                -> ":closed_lock_with_key:"
      )

      val service = new AlertingService(configuration, slackConnector)

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
            description = "uses nulls!",
            lineText    = " var x = null",
            matches     = List(Match(9, 13, "null"))
          ))
      )

      service.alert(report)

      val expectedSlackMessage = SlackMessage(
        channel     = "#the-channel",
        text        = "Do not panic, but there is a leak!",
        username    = "leak-detection",
        icon_emoji  = ":closed_lock_with_key:",
        attachments = Seq(Attachment(s"https://somewhere/reports/${report._id}"))
      )
      verify(slackConnector).sendMessage(is(expectedSlackMessage))(any())
    }
  }
}
