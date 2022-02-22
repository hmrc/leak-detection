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

import play.api.Logger
import play.api.libs.json.{Format, JsValue, Json}
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{Branch, Report, Repository}
import uk.gov.hmrc.leakdetection.services.{LeaksService, RescanService}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdminController @Inject()(
                                 configLoader:    ConfigLoader,
                                 leaksService:    LeaksService,
                                 rescanService:   RescanService,
                                 httpClient:      HttpClient,
                                 cc:              ControllerComponents
                               )(implicit ec: ExecutionContext) extends BackendController(cc) {

  val logger = Logger(this.getClass.getName)

  import configLoader.cfg

  def rescanRepo(repository: Repository, branch: Branch, dryRun: Option[Boolean]) = Action.async { implicit request =>
    implicit val rf: Format[Report] = Report.apiFormat
    rescanService.rescan(repository, branch, dryRun)
      .flatMap {
        case Some(f) => f.map(r => Ok(Json.toJson(r)))
        case _ => Future.successful(NotFound(s"rescan could not be performed as '${repository.asString}' is not a known HMRC repository"))
      }
  }

  def rescan() = Action.async(parse.json) { implicit request =>
    request.body.validate[List[String]].fold(
      _     => Future.successful(BadRequest("Invalid list of repos")),
      repos => rescanService.triggerRescan(repos).map(_ => Accepted(""))
    )
  }

  def checkGithubRateLimits = Action.async { implicit request =>
    val authorizationHeader =
      hc.withExtraHeaders("Authorization" -> s"token ${cfg.githubSecrets.personalAccessToken}")

    httpClient
      .GET[JsValue]("https://api.github.com/rate_limit")(implicitly, authorizationHeader, implicitly)
      .map(Ok(_))
  }

  def stats = Action.async {
    leaksService.metrics.map(stats => Ok(Json.toJson(stats)))
  }
}

object AdminController {
  val NOT_APPLICABLE = "n/a"
}

final case class AcceptanceTestsRequest(fileContent: String, fileName: String)

object AcceptanceTestsRequest {
  implicit val format: Format[AcceptanceTestsRequest] =
    Json.format[AcceptanceTestsRequest]
}
