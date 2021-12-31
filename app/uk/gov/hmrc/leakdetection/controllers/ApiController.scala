/*
 * Copyright 2021 HM Revenue & Customs
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
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.leakdetection.model.{Report, ReportId, Repository}
import uk.gov.hmrc.leakdetection.services.ReportsService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ApiController @Inject()(reportsService: ReportsService, cc: ControllerComponents)(implicit val ec: ExecutionContext) extends BackendController(cc) {

  private implicit val rptf = Report.apiFormat

  def repositories(): Action[AnyContent] = Action.async { implicit request =>
    reportsService
      .getRepositories
      .map(r => Ok(Json.toJson(r)))
  }

  def latestReportsForEachBranch(repository: Repository): Action[AnyContent] = Action.async { implicit request =>
    reportsService
      .getLatestReportsForEachBranch(repository)
      .map(r => Ok(Json.toJson(r)))
  }

  def latestReportForDefaultBranch(repository: Repository): Action[AnyContent] = Action.async { implicit request =>
    reportsService
      .getLatestReportForDefaultBranch(repository)
      .map(r => Ok(Json.toJson(r)))
  }

  def report(reportId: ReportId): Action[AnyContent] = Action.async { implicit request =>
    reportsService
      .getReport(reportId)
      .map(_.fold(NotFound("No report found."))(r => Ok(Json.toJson(r))))
  }

}
