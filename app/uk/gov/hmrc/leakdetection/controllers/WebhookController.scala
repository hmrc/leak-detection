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

package uk.gov.hmrc.leakdetection.controllers

import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.mvc.{BodyParser, ControllerComponents}
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{RepositoryEvent, DeleteBranchEvent, GithubRequest, PayloadDetails, ZenMessage}
import uk.gov.hmrc.leakdetection.services.{ActiveBranchesService, LeaksService, ReportsService, ScanningService, WarningsService}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class WebhookController @Inject()(
  configLoader: ConfigLoader,
  scanningService: ScanningService,
  reportsService: ReportsService,
  leakService: LeaksService,
  warningsService: WarningsService,
  activeBranchesService: ActiveBranchesService,
  webhookRequestValidator: WebhookRequestValidator,
  cc: ControllerComponents)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

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
          for {
            _ <- activeBranchesService.clearAfterBranchDeleted(deleteBranchEvent)
            _ <- reportsService.clearReportsAfterBranchDeleted(deleteBranchEvent)
            _ <- leakService.clearLeaksAfterBranchDeleted(deleteBranchEvent)
            _ <- warningsService.clearWarningsAfterBranchDeleted(deleteBranchEvent)
          } yield Ok (toJson (WebhookResponse ("report(s) successfully cleared")))

        case repositoryEvent: RepositoryEvent if !repositoryEvent.action.equalsIgnoreCase("deleted") =>
          Future.successful(
            Ok(toJson(WebhookResponse(s"Repository events with ${repositoryEvent.action} actions are ignored")))
          )

        case repositoryEvent: RepositoryEvent =>
          for {
            _ <- activeBranchesService.clearAfterRepoDeleted(repositoryEvent.repositoryName)
            _ <- leakService.clearAllLeaksAfterRepoDeleted(repositoryEvent.repositoryName)
            _ <- warningsService.clearWarningsAfterRepoDeleted(repositoryEvent.repositoryName)
          } yield Ok( toJson(WebhookResponse(s"reports(s) for ${repositoryEvent.repositoryName} successfully cleared")))

        case ZenMessage(_) =>
          Future.successful(
            Ok(toJson(WebhookResponse("Zen message ignored")))
          )
      }

    }

  val parseGithubRequest: BodyParser[GithubRequest] =
    webhookRequestValidator.parser(configLoader.cfg.githubSecrets.webhookSecretKey)

}

case class WebhookResponse(details: String)
