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

import ammonite.ops.{mkdir, tmp, write}
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.{Format, Json}
import play.api.mvc.Action
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.leakdetection.services.{ReportsService, ScanningService}
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

@Singleton
class AdminController @Inject()(
  configLoader: ConfigLoader,
  scanningService: ScanningService,
  reportsService: ReportsService)
    extends BaseController {

  val logger = Logger(this.getClass.getName)

  import configLoader.cfg.allRules._

  def rules() = Action {
    Ok(Json.toJson(configLoader.cfg.allRules))
  }

  def validate(repository: String, branch: String, isPrivate: Boolean) = Action.async { implicit request =>
    scanningService
      .scanRepository(
        repository    = repository,
        branch        = branch,
        isPrivate     = isPrivate,
        repositoryUrl = s"https://github.com/hmrc/$repository",
        commitId      = "n/a",
        authorName    = "n/a",
        archiveUrl    = s"https://api.github.com/repos/hmrc/$repository/{archive_format}{/ref}"
      )
      .map { report =>
        Ok(Json.toJson(report))
      }
  }

  def testPublicRules() = testRules(publicRules)

  def testPrivateRules() = testRules(privateRules)

  private def testRules(rules: List[Rule]) =
    Action(parse.json) { implicit request =>
      val acceptanceTestsRequest = request.body.as[AcceptanceTestsRequest]
      logger.info(s"Checking:\n ${request.body}")

      val simulatedExplodedDir = tmp.dir()
      val repoDir              = simulatedExplodedDir / "repo_dir"
      mkdir ! repoDir
      write(repoDir / acceptanceTestsRequest.fileName, acceptanceTestsRequest.fileContent)

      val regexMatchingEngine = new RegexMatchingEngine(rules)
      val results             = regexMatchingEngine.run(simulatedExplodedDir.toIO).map(_.scanResults)

      Ok(Json.toJson(results))
    }

  def clearCollection() = Action.async { implicit request =>
    reportsService.clearCollection().map { res =>
      Ok(s"ok=${res.ok}, errors = ${res.writeErrors}")
    }
  }
}

final case class AcceptanceTestsRequest(fileContent: String, fileName: String)

object AcceptanceTestsRequest {
  implicit val format: Format[AcceptanceTestsRequest] =
    Json.format[AcceptanceTestsRequest]
}
