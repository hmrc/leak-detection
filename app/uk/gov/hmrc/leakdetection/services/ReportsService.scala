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

package uk.gov.hmrc.leakdetection.services

import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.leakdetection.model.ReportId
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository

class ReportsService @Inject()(reportsRepository: ReportsRepository)(
  implicit ec: ExecutionContext) {

  def getRepositories = reportsRepository.getDistinctRepoNames

  def getReports(repoName: String) = reportsRepository.findByRepoName(repoName)

  def getReport(reportId: ReportId) = reportsRepository.findByReportId(reportId)
}
