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

import java.io.File

import ammonite.ops.{Path, mkdir, tmp, write}
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.{Format, JsValue, Json}
import play.api.mvc.Action
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.leakdetection.services.{ReportsService, ScanningService}
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future

@Singleton
class AdminController @Inject()(
  configLoader: ConfigLoader,
  scanningService: ScanningService,
  reportsService: ReportsService,
  httpClient: HttpClient)
    extends BaseController {

  val logger = Logger(this.getClass.getName)

  import configLoader.cfg
  import AdminController._

  def rules() = Action {
    Ok(Json.toJson(cfg.allRules))
  }

  def validate(repository: String, branch: String, isPrivate: Boolean) = Action.async { implicit request =>
    scanningService
      .scanRepository(
        repository    = repository,
        branch        = branch,
        isPrivate     = isPrivate,
        repositoryUrl = s"https://github.com/hmrc/$repository",
        commitId      = NOT_APPLICABLE,
        authorName    = NOT_APPLICABLE,
        archiveUrl    = s"https://api.github.com/repos/hmrc/$repository/{archive_format}{/ref}"
      )
      .map { report =>
        Ok(Json.toJson(report))
      }
  }

  def testPublicRules() = testRules(cfg.allRules.publicRules)

  def testPrivateRules() = testRules(cfg.allRules.privateRules)

  private def testRules(rules: List[Rule]) =
    Action(parse.json) { implicit request =>
      val acceptanceTestsRequest = request.body.as[AcceptanceTestsRequest]
      logger.info(s"Checking:\n ${request.body}")

      val simulatedExplodedDir = createFiles(acceptanceTestsRequest.fileName, acceptanceTestsRequest.fileContent)
      val regexMatchingEngine  = new RegexMatchingEngine(rules, cfg.maxLineLength)
      val results              = regexMatchingEngine.run(simulatedExplodedDir).map(_.scanResults)

      Ok(Json.toJson(results))
    }

  private def createFiles(fileName: String, fileContent: String): File = {
    val simulatedExplodedDir = tmp.dir()
    val repoDir              = simulatedExplodedDir / "repo_dir"
    mkdir ! repoDir
    val filePathSegments = fileName.split("/").filterNot(_.isEmpty)
    val actualFile = filePathSegments.foldLeft(repoDir: Path) { (acc, current) =>
      acc / current
    }
    write(actualFile, fileContent)
    simulatedExplodedDir.toIO
  }

  def clearCollection() = Action.async { implicit request =>
    if (configLoader.cfg.clearingCollectionEnabled) {
      reportsService.clearCollection().map { res =>
        Ok(s"ok=${res.ok}, records deleted=${res.n}, errors = ${res.writeErrors}")
      }
    } else {
      Future.successful(Ok("Clearing reports is disabled."))
    }
  }

  def checkGithubRateLimits = Action.async { implicit request =>
    val authorizationHeader =
      hc.withExtraHeaders("Authorization" -> s"token ${cfg.githubSecrets.personalAccessToken}")

    httpClient
      .GET[JsValue]("https://api.github.com/rate_limit")(implicitly, authorizationHeader, implicitly)
      .map(Ok(_))
  }

  def stats = Action.async { implicit request =>
    reportsService.metrics.map(stats => Ok(Json.toJson(stats)))
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
