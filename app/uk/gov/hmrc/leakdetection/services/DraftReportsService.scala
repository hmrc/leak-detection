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

import play.api.Configuration
import uk.gov.hmrc.leakdetection.config.Rule
import uk.gov.hmrc.leakdetection.model.{Report, ReportId}
import uk.gov.hmrc.leakdetection.persistence.DraftReportsRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class DraftReportsService @Inject() (draftRepository: DraftReportsRepository, configuration: Configuration){

  lazy val repositoriesToIgnore: Seq[String] =
    configuration.getOptional[Seq[String]]("shared.repositories").getOrElse(List.empty)

  def saveReport(report: Report): Future[Unit] =
    draftRepository.saveReport(report)

  def clearDrafts(): Future[Long] =
    draftRepository.removeAll()

  def getDraftReport(reportId: ReportId): Future[Option[Report]] =
    draftRepository.findByReportId(reportId)

  def findDraftReportsForRule(rule: Rule): Future[Seq[Report]] =
    draftRepository.findAllWithRuleViolation(rule.id)

  def findDraftReportsWithViolations(): Future[Seq[Report]] =
    draftRepository.findAllWithAnyRuleViolation()

  def findAllDraftReports(): Future[Seq[Report]] =
    draftRepository.findAll()
}
