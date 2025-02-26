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

import play.api.libs.json.{Format, Json, OFormat}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.services._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ApiController @Inject()(
  reportsService : ReportsService,
  leaksService   : LeaksService,
  warningsService: WarningsService,
  summaryService : SummaryService,
  ruleService    : RuleService,
  cc             : ControllerComponents
)(using ExecutionContext
) extends BackendController(cc):

  def leaks(
    repository: Option[String],
    branch    : Option[String],
    ruleId    : Option[String]
  ): Action[AnyContent] =
    Action.async:
      given OFormat[Leak] = Leak.apiFormat
      leaksService
        .getLeaks(
          repoName = repository,
          branch   = branch,
          ruleId   = ruleId
        )
        .map(r => Ok(Json.toJson(r)))

  def rules: Action[AnyContent] =
    Action:
      Ok(Json.toJson(ruleService.getAllRules))

  def ruleSummary(
    ruleId        : Option[String],
    repository    : Option[String],
    team          : Option[String],
    digitalService: Option[String],
  ): Action[AnyContent] =
    Action.async:
      given OFormat[Summary] = Summary.apiFormat
      summaryService
        .getRuleSummaries(
          ruleId         = ruleId,
          repoName       = repository,
          teamName       = team,
          digitalService = digitalService
        )
        .map(r => Ok(Json.toJson(r)))

  def repositorySummary(
    ruleId          : Option[String],
    repository      : Option[String],
    team            : Option[String],
    digitalService  : Option[String],
    excludeNonIssues: Boolean,
    includeBranches : Boolean
  ): Action[AnyContent] =
    Action.async:
      given OFormat[RepositorySummary] = RepositorySummary.format
      summaryService
        .getRepositorySummaries(
          ruleId           = ruleId,
          repoName         = repository,
          teamName         = team,
          digitalService   = digitalService,
          excludeNonIssues = excludeNonIssues,
          includeBranches  = includeBranches
        )
        .map(rs => Ok(Json.toJson(rs)))

  def reportLeaks(reportId: ReportId): Action[AnyContent] =
    Action.async:
      given OFormat[Leak] = Leak.apiFormat
      leaksService
        .getLeaksForReport(reportId)
        .map(l => Ok(Json.toJson(l)))

  def reportWarnings(reportId: ReportId): Action[AnyContent] =
    Action.async:
      given OFormat[Warning] = Warning.apiFormat
      warningsService
        .getWarningsForReport(reportId)
        .map(w => Ok(Json.toJson(w)))

  def latestReport(
    repository: Repository,
    branch    : Branch
  ): Action[AnyContent] =
    Action.async:
      given Format[Report] = Report.apiFormat
      reportsService
        .getLatestReport(repository, branch)
        .map(_.fold(NotFound("No report found."))(r => Ok(Json.toJson(r))))

  def repositories: Action[AnyContent] =
    Action.async:
      leaksService
        .getRepositoriesWithUnresolvedLeaks
        .map(r => Ok(Json.toJson(r)))

  def report(reportId: ReportId): Action[AnyContent] =
    Action.async:
      given Format[Report] = Report.apiFormat
      reportsService
        .getReport(reportId)
        .map(_.fold(NotFound("No report found."))(r => Ok(Json.toJson(r))))
