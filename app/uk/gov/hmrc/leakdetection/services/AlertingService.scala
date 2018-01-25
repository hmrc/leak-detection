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

import javax.inject.Inject

import play.api.{Configuration, Logger}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.connectors.{Attachment, SlackConnector, SlackMessage}
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

import scala.concurrent.Future

class AlertingService @Inject()(configuration: Configuration, slackConnector: SlackConnector) {

  def alert(report: Report)(implicit hc: HeaderCarrier): Future[Boolean] =
    if (!configuration.getBoolean("alerts.slack.enabled").getOrElse(false)) {
      Logger.debug("Slack alerts are disabled... not sending a notification")
      Future.successful(true)

    } else if (report.inspectionResults.isEmpty) {
      Logger.debug("Slack alerts are disabled... not sending a notification")
      Future.successful(true)

    } else {

      val slackChannel  = getConfigOrFail("alerts.slack.defaultAlertChannel.name")
      val slackUsername = getConfigOrFail("alerts.slack.user.name")
      val slackIcon     = getConfigOrFail("alerts.slack.user.icon")
      val reportUri     = getConfigOrFail("leakDetection.uri")
      val alertMessage =
        getConfigOrFail("alerts.slack.message.text")
          .replace("{repo}", report.repoName)
          .replace("{branch}", report.branch)

      val message =
        SlackMessage(
          channel     = slackChannel,
          text        = alertMessage,
          username    = slackUsername,
          icon_emoji  = slackIcon,
          attachments = Seq(Attachment(s"$reportUri/reports/${report._id}")))

      slackConnector.sendMessage(message).map(_.status == 200)
    }

  private def getConfigOrFail(key: String): String =
    configuration
      .getString(key)
      .getOrElse(throw new RuntimeException("Unable to send an alert to slack. Missing configuration: " + key))
}
