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

import play.api.libs.json.{Format, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.leakdetection.model.{Report, ReportId}
import uk.gov.hmrc.leakdetection.services.{DraftReportsService, RuleService}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DraftReportController @Inject()(
  draftReportsService: DraftReportsService,
  ruleService        : RuleService,
  cc                 : ControllerComponents
)(using ExecutionContext
) extends BackendController(cc):
  given Format[Report] = Report.apiFormat

  def findDraftReports(rule: Option[String]): Action[AnyContent] =
    Action.async:
      rule match
        case None        => draftReportsService.findAllDraftReports().map(d => Ok(Json.toJson(d)))
        case Some("any") => draftReportsService.findDraftReportsWithViolations().map(d => Ok(Json.toJson(d)))
        case Some(r)     => ruleService
                              .getAllRules
                              .find(_.id == r)
                              .fold(
                                ifEmpty = Future.successful(BadRequest("Unknown ruleId"))
                              )(
                                rule => draftReportsService.findDraftReportsForRule(rule).map(d => Ok(Json.toJson(d)))
                              )

  def draftReport(reportId: ReportId): Action[AnyContent] =
    Action.async:
      draftReportsService
        .getDraftReport(reportId)
        .map(_.fold(NotFound("No report found."))(r => Ok(Json.toJson(r))))

  def clearAllDrafts(): Action[AnyContent] =
    Action.async:
      draftReportsService.clearDrafts().map(_ => Ok("all drafts deleted"))
