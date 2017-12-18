/*
 * Copyright 2017 HM Revenue & Customs
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

import javax.inject.Inject

import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.Action
import uk.gov.hmrc.leakdetection.config.{AllRules, ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.scanner.RegexScanner
import uk.gov.hmrc.leakdetection.services.{ReportsService, ScanningService}
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future

class AdminController @Inject()(
  configLoader: ConfigLoader,
  scanningService: ScanningService,
  reportsService: ReportsService)
    extends BaseController {

  val logger = Logger(this.getClass.getName)

  import configLoader.cfg.allRules._

  implicit val ruleWrites = AllRules.f

  def rules() = Action {
    Ok(Json.toJson(configLoader.cfg.allRules))
  }

  def validatePrivate(repository: String, branch: String) = Action.async { implicit request =>
    scanningService
      .scanRepository(
        repository    = repository,
        branch        = branch,
        isPrivate     = true,
        repositoryUrl = s"https://github.com/hmrc/$repository",
        commitId      = "NA",
        authorName    = "NA",
        archiveUrl    = s"https://api.github.com/repos/hmrc/$repository/{archive_format}{/ref}"
      )
      .map { report =>
        Ok(Json.toJson(report))
      }
  }

  def testPublicRules() = testRules(publicRules)

  def testPrivateRules() = testRules(privateRules)

  private def testRules(rules: List[Rule]) =
    Action(parse.tolerantText) { implicit request =>
      logger.info(s"Checking:\n ${request.body}")

      val fileContentScanners = rules.filter(_.scope == Rule.Scope.FILE_CONTENT).map(new RegexScanner(_))
      val fileNameScanners    = rules.filter(_.scope == Rule.Scope.FILE_NAME).map(new RegexScanner(_))

      val matchesByContent = fileContentScanners.flatMap(_.scanFileContent(request.body))
      val matchesByName    = fileNameScanners.flatMap(_.scanFileName(request.body))

      Ok(Json.toJson(matchesByContent ++ matchesByName))
    }

  def clearCollection() = Action.async { implicit request =>
    reportsService.clearCollection().map { res =>
      Ok(s"ok=${res.ok}, errors = ${res.writeErrors}")
    }
  }
}
