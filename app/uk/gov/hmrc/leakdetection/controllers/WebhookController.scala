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
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model.{DeleteBranchEvent, GithubRequest, PayloadDetails, Repository, RepositoryEvent, RunMode, ZenMessage}
import uk.gov.hmrc.leakdetection.services.{ActiveBranchesService, LeaksService, ReportsService, RescanService, ScanningService, WarningsService}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class WebhookController @Inject()(
  appConfig                    : AppConfig,
  scanningService              : ScanningService,
  reportsService               : ReportsService,
  leakService                  : LeaksService,
  warningsService              : WarningsService,
  activeBranchesService        : ActiveBranchesService,
  webhookRequestValidator      : WebhookRequestValidator,
  rescanService                : RescanService,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  cc                           : ControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(cc) {

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
            _ <- activeBranchesService.clearBranch(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
            _ <- reportsService.clearReportsAfterBranchDeleted(deleteBranchEvent)
            _ <- leakService.clearBranchLeaks(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
            _ <- warningsService.clearBranchWarnings(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
          } yield Ok (toJson (WebhookResponse (s"${deleteBranchEvent.repositoryName}/${deleteBranchEvent.branchRef} deleted")))

        case repositoryEvent: RepositoryEvent if repositoryEvent.action.equalsIgnoreCase("archived") =>
          for {
            _             <- leakService.clearRepoLeaks(repositoryEvent.repositoryName)
            _             <- warningsService.clearRepoWarnings(repositoryEvent.repositoryName)
            _             <- rescanService.rescanArchivedBranches(Repository(repositoryEvent.repositoryName), RunMode.Normal)
            defaultBranch <- teamsAndRepositoriesConnector.repo(repositoryEvent.repositoryName).map(_.map(_.defaultBranch).getOrElse("main"))
            _             <- activeBranchesService.clearRepoExceptDefault(repositoryEvent.repositoryName, defaultBranch)
          } yield Ok( toJson(WebhookResponse(s"${repositoryEvent.repositoryName} archived")))

        case repositoryEvent: RepositoryEvent if repositoryEvent.action.equalsIgnoreCase("deleted") =>
          for {
            _ <- activeBranchesService.clearRepo(repositoryEvent.repositoryName)
            _ <- leakService.clearRepoLeaks(repositoryEvent.repositoryName)
            _ <- warningsService.clearRepoWarnings(repositoryEvent.repositoryName)
          } yield Ok( toJson(WebhookResponse(s"${repositoryEvent.repositoryName} deleted")))

        case repositoryEvent: RepositoryEvent =>
          Future.successful(
            Ok(toJson(WebhookResponse(s"Repository events with ${repositoryEvent.action} actions are ignored")))
          )

        case ZenMessage(_) =>
          Future.successful(
            Ok(toJson(WebhookResponse("Zen message ignored")))
          )
      }

    }

  val parseGithubRequest: BodyParser[GithubRequest] =
    webhookRequestValidator.parser(appConfig.githubSecrets.webhookSecretKey)

}

case class WebhookResponse(details: String)
