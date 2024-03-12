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

import play.api.Logger
import play.api.libs.json.{Json, OFormat}
import play.api.libs.json.Json.toJson
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.leakdetection.config.AppConfig
import uk.gov.hmrc.leakdetection.connectors.TeamsAndRepositoriesConnector
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.services._
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

  private val logger = Logger(getClass)

  implicit val responseF: OFormat[WebhookResponse] = Json.format[WebhookResponse]

  def processGithubWebhook(): Action[GithubRequest] =
    Action.async(parse.json[GithubRequest](GithubRequest.githubReads)) { implicit request =>
      request.body match {

        case pushUpdate: PushUpdate if pushUpdate.branchRef.contains("refs/tags/") =>
          Future.successful(
            Ok(toJson(WebhookResponse("Tag commit ignored")))
          )

        case pushUpdate: PushUpdate =>
          scanningService.queueDistinctRequest(pushUpdate).map { duplicate =>
            if (duplicate) {
              logger.info(s"Duplicate github webhook event - repo: ${pushUpdate.repositoryName}, branch: ${pushUpdate.branchRef}, commit: ${pushUpdate.commitId}")
              Ok(toJson(WebhookResponse("Duplicate request ignored")))
            } else
              Ok(toJson(WebhookResponse("Request successfully queued")))
          }

        case pushDelete: PushDelete =>
          for {
            _ <- activeBranchesService.clearBranch(pushDelete.repositoryName, pushDelete.branchRef)
            _ <- reportsService.clearReportsAfterBranchDeleted(pushDelete)
            _ <- leakService.clearBranchLeaks(pushDelete.repositoryName, pushDelete.branchRef)
            _ <- warningsService.clearBranchWarnings(pushDelete.repositoryName, pushDelete.branchRef)
            _ <- rescanService.clearBranch(pushDelete.repositoryName, pushDelete.branchRef)
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
