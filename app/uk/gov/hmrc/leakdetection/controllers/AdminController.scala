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

import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.leakdetection.connectors.GithubConnector
import uk.gov.hmrc.leakdetection.model.{Branch, Report, Repository, RunMode}
import uk.gov.hmrc.leakdetection.services.{LeaksService, RescanService}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdminController @Inject()(
  leaksService   : LeaksService,
  rescanService  : RescanService,
  githubConnector: GithubConnector,
  cc             : ControllerComponents
)(using ExecutionContext
) extends BackendController(cc):

  def rescanRepo(repository: Repository, branch: Branch, runMode: RunMode): Action[AnyContent] =
    Action.async:
      implicit request =>
        given Format[Report] = Report.apiFormat

        rescanService.rescan(repository, branch, runMode)
          .flatMap:
            case Some(f) => f.map(r => Ok(Json.toJson(r)))
            case _ => Future.successful(NotFound(s"rescan could not be performed as '${repository.asString}' is not a known HMRC repository"))

  def rescan(runMode: RunMode): Action[JsValue] =
    Action.async(parse.json):
      implicit request =>
        request.body.validate[List[String]].fold(
          _     => Future.successful(BadRequest("Invalid list of repos")),
          repos => rescanService.triggerRescan(repos, runMode).map(_ => Accepted(""))
        )

  def rescanAllRepos(runMode: RunMode): Action[JsValue] =
    Action.async(parse.json): _ =>
      rescanService.rescanAllRepos(runMode).map(_ => Accepted(""))

  def checkGithubRateLimits: Action[AnyContent] =
    Action.async:
      githubConnector
        .getRateLimit()
        .map(Ok(_))

  def stats: Action[AnyContent] =
    Action.async:
      leaksService.metrics.map(stats => Ok(Json.toJson(stats)))

object AdminController:
  val NOT_APPLICABLE = "n/a"

case class AcceptanceTestsRequest(fileContent: String, fileName: String)

object AcceptanceTestsRequest:
  given Format[AcceptanceTestsRequest] =
    Json.format[AcceptanceTestsRequest]
