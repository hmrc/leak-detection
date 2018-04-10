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

package uk.gov.hmrc.leakdetection.controllers

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.mvc.{Action, BodyParser}

import scala.concurrent.Future
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{DeleteBranchEvent, GithubRequest, PayloadDetails, ZenMessage}
import uk.gov.hmrc.leakdetection.services.ReportsService.ClearingReportsResult
import uk.gov.hmrc.leakdetection.services.{ReportsService, ScanningService}
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

@Singleton
class WebhookController @Inject()(
  configLoader: ConfigLoader,
  scanningService: ScanningService,
  reportsService: ReportsService
) extends BaseController {

  implicit val responseF = Json.format[WebhookResponse]

  def processGithubWebhook() =
    Action.async(parseGithubRequest) { implicit request =>
      request.body match {

        case payloadDetails: PayloadDetails if payloadDetails.branchRef.contains("refs/tags/") =>
          Future.successful(
            Ok(toJson(WebhookResponse("Tag commit ignored")))
          )

        case payloadDetails: PayloadDetails =>
          scanningService.queueRequest(payloadDetails).map { _ =>
            Ok(toJson(WebhookResponse("Request successfully queued")))
          }

        case deleteBranchEvent: DeleteBranchEvent =>
          reportsService
            .clearReportsAfterBranchDeleted(deleteBranchEvent)
            .map {
              case ClearingReportsResult(_, reports) =>
                Ok(toJson(WebhookResponse(s"${reports.size} report(s) successfully cleared")))
            }

        case ZenMessage(_) =>
          Future.successful(
            Ok(toJson(WebhookResponse("Zen message ignored")))
          )
      }

    }

  val parseGithubRequest: BodyParser[GithubRequest] =
    WebhookRequestValidator.parser(
      webhookSecret = configLoader.cfg.githubSecrets.webhookSecretKey
    )

}

case class WebhookResponse(details: String)
