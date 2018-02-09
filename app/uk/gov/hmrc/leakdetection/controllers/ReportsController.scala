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

import javax.inject.Singleton
import com.google.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.Action
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.ReportId
import uk.gov.hmrc.leakdetection.services.ReportsService
import uk.gov.hmrc.leakdetection.views.html
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

@Singleton
class ReportsController @Inject()(configLoader: ConfigLoader, reportsService: ReportsService) extends BaseController {

  def repositories = Action.async { implicit request =>
    reportsService.getRepositories.map { repoNames =>
      Ok(html.repo_list(repoNames))
    }
  }

  def reportsForRepository(repoName: String) = Action.async { implicit request =>
    reportsService.getReports(repoName).map { reports =>
      Ok(html.reports_for_repo(repoName, reports))
    }
  }

  def redirectToRepositories = Action {
    Redirect(routes.ReportsController.repositories())
  }

  def showReport(reportId: ReportId) = Action.async { implicit request =>
    reportsService.getReport(reportId).map { maybeReport =>
      maybeReport
        .map { r =>
          Ok(html.report(r, configLoader.cfg.leakResolutionSteps))
        }
        .getOrElse(NotFound(Json.obj("msg" -> s"Report w/id $reportId not found")))
    }
  }
}
