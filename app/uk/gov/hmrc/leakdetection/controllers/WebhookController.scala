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

package uk.gov.hmrc.leakdetection.controllers

import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model.{GithubRequest, PushDelete, PushUpdate, Repository, RepositoryEvent, RunMode}
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
  rescanService                : RescanService,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  cc                           : ControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(cc) {

  implicit val responseF = Json.format[WebhookResponse]

  def processGithubWebhook() =
    Action.async(parse.json[GithubRequest](GithubRequest.githubReads)) { implicit request =>
      request.body match {

        case pushUpdate: PushUpdate if pushUpdate.branchRef.contains("refs/tags/") =>
          Future.successful(
            Ok(toJson(WebhookResponse("Tag commit ignored")))
          )

        case pushUpdate: PushUpdate =>
          scanningService.queueRequest(pushUpdate).map { _ =>
            Ok(toJson(WebhookResponse("Request successfully queued")))
          }

        case pushDelete: PushDelete =>
          for {
            _ <- activeBranchesService.clearBranch(pushDelete.repositoryName, pushDelete.branchRef)
            _ <- reportsService.clearReportsAfterBranchDeleted(pushDelete)
            _ <- leakService.clearBranchLeaks(pushDelete.repositoryName, pushDelete.branchRef)
            _ <- warningsService.clearBranchWarnings(pushDelete.repositoryName, pushDelete.branchRef)
          } yield Ok (toJson (WebhookResponse(s"${pushDelete.repositoryName}/${pushDelete.branchRef} deleted")))

        case repositoryEvent: RepositoryEvent if repositoryEvent.action.equalsIgnoreCase("archived") =>
          for {
            _             <- leakService.clearRepoLeaks(repositoryEvent.repositoryName)
            _             <- warningsService.clearRepoWarnings(repositoryEvent.repositoryName)
            _             <- rescanService.rescanArchivedBranches(Repository(repositoryEvent.repositoryName), RunMode.Normal)
            defaultBranch <- teamsAndRepositoriesConnector.repo(repositoryEvent.repositoryName).map(_.fold("main")(_.defaultBranch))
            _             <- activeBranchesService.clearRepoExceptDefault(repositoryEvent.repositoryName, defaultBranch)
          } yield Ok(toJson(WebhookResponse(s"${repositoryEvent.repositoryName} archived")))

        case repositoryEvent: RepositoryEvent if repositoryEvent.action.equalsIgnoreCase("deleted") =>
          for {
            _ <- activeBranchesService.clearRepo(repositoryEvent.repositoryName)
            _ <- leakService.clearRepoLeaks(repositoryEvent.repositoryName)
            _ <- warningsService.clearRepoWarnings(repositoryEvent.repositoryName)
          } yield Ok(toJson(WebhookResponse(s"${repositoryEvent.repositoryName} deleted")))

        case repositoryEvent: RepositoryEvent =>
          Future.successful(
            Ok(toJson(WebhookResponse(s"Repository events with ${repositoryEvent.action} actions are ignored")))
          )
      }
    }
}

case class WebhookResponse(details: String)
