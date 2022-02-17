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

package uk.gov.hmrc.leakdetection.services

import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.WarningRepository

import java.io.File
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class WarningsService @Inject()(configLoader: ConfigLoader,
                                repoVisibilityChecker: RepoVisibilityChecker,
                                warningRepository: WarningRepository
                               )(implicit ec: ExecutionContext) {

  import configLoader.cfg

  def saveWarnings(repository: Repository, branch: Branch, warnings: Seq[Warning]): Future[Unit] =
    warningRepository.update(repository.asString, branch.asString, warnings).map(_ => ())

  def getWarnings(repoName: Option[String], branch: Option[String]): Future[Seq[Warning]] =
    warningRepository.findBy(repoName, branch)


  def checkForWarnings(report: Report, dir: File, isPrivate: Boolean): Seq[Warning] = {
    Seq(
      repoVisibilityChecker.checkVisibilityDefinedCorrectly(dir, isPrivate),
      checkFileLevelExemptions(dir, isPrivate)
    )
      .flatten
      .map(w => Warning(report.repoName, report.branch, report.timestamp, report.id, w.toString))
  }

  def checkFileLevelExemptions(dir: File, isPrivate: Boolean): Option[WarningMessageType] = {
    val ruleSet = if (isPrivate) cfg.allRules.privateRules else cfg.allRules.publicRules
    val exemptions = RulesExemptionParser.parseServiceSpecificExemptions(FileAndDirectoryUtils.getSubdirName(dir))

    def isFileContentRule(ruleId: String): Boolean = ruleSet.filter(_.scope == Rule.Scope.FILE_CONTENT).exists(_.id == ruleId)

    if (exemptions
      .filter(e => isFileContentRule(e.ruleId))
      .exists(_.text.isEmpty)) {
      Some(FileLevelExemptions)
    } else {
      None
    }
  }

}