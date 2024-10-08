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

package uk.gov.hmrc.leakdetection.services

import uk.gov.hmrc.leakdetection.config.{AppConfig, Rule, RuleExemption}
import uk.gov.hmrc.leakdetection.model.*
import uk.gov.hmrc.leakdetection.model.WarningMessageType._
import uk.gov.hmrc.leakdetection.persistence.WarningRepository

import java.io.File
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class WarningsService @Inject()(
  appConfig: AppConfig,
  repoVisibilityChecker: RepoVisibilityChecker,
  warningRepository: WarningRepository
)(using ExecutionContext):

  def saveWarnings(repository: Repository, branch: Branch, warnings: Seq[Warning]): Future[Unit] =
    warningRepository.update(repository.asString, branch.asString, warnings).map(_ => ())

  def clearBranchWarnings(repoName: String, branchName: String): Future[Long] =
    warningRepository.removeWarnings(repoName, branchName)

  def clearRepoWarnings(repoName: String): Future[Long] =
    warningRepository.removeWarnings(repoName)

  def getWarnings(repoName: Option[String], branch: Option[String]): Future[Seq[Warning]] =
    warningRepository.findBy(repoName, branch)

  def getWarningsForReport(reportId: ReportId): Future[Seq[Warning]] =
    warningRepository
      .findForReport(reportId.value)
      .map:
        _.map: warning =>
          warning.copy(warningMessageType =
            appConfig.warningMessages.getOrElse(warning.warningMessageType, warning.warningMessageType))

  def checkForWarnings(
    report: Report,
    dir: File,
    isPrivate: Boolean,
    isArchived: Boolean,
    exemptions: List[RuleExemption],
    additionalWarnings: Seq[WarningMessageType]): Seq[Warning] =
    (Seq(
      repoVisibilityChecker.checkVisibility(dir, isPrivate, isArchived),
      checkFileLevelExemptions(isPrivate, exemptions),
      checkUnusedExemptions(report, isArchived)
    ).flatten ++ additionalWarnings).distinct
      .map(w => Warning(report.repoName, report.branch, report.timestamp, report.id, w.toString))

  private def checkFileLevelExemptions(
    isPrivate: Boolean,
    exemptions: List[RuleExemption]): Option[WarningMessageType] =
    val ruleSet =
      if isPrivate then appConfig.allRules.privateRules
      else appConfig.allRules.publicRules

    def isFileContentRule(ruleId: String): Boolean =
      ruleSet.filter(_.scope == Rule.Scope.FILE_CONTENT).exists(_.id == ruleId)

    if exemptions.filter(e => isFileContentRule(e.ruleId)).exists(_.text.isEmpty) then
      Some(FileLevelExemptions)
    else
      None

  private def checkUnusedExemptions(report: Report, isArchived: Boolean): Option[WarningMessageType] =
    if isArchived || report.unusedExemptions.isEmpty then
      None
    else
      Some(UnusedExemptions)
