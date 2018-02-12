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

package uk.gov.hmrc.leakdetection.services

import javax.inject.{Inject, Singleton}
import org.apache.commons.io.FileUtils
import play.api.Configuration
import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{PayloadDetails, Report}
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

@Singleton
class ScanningService @Inject()(
  configuration: Configuration,
  artifactService: ArtifactService,
  configLoader: ConfigLoader,
  reportsRepository: ReportsRepository,
  alertingService: AlertingService
) {
  lazy val privateMatchingEngine: RegexMatchingEngine = new RegexMatchingEngine(configLoader.cfg.allRules.privateRules)
  lazy val publicMatchingEngine: RegexMatchingEngine  = new RegexMatchingEngine(configLoader.cfg.allRules.publicRules)

  def scanRepository(
    repository: String,
    branch: String,
    isPrivate: Boolean,
    repositoryUrl: String,
    commitId: String,
    authorName: String,
    archiveUrl: String)(implicit hc: HeaderCarrier): Future[Report] = {

    val explodedZipDir = artifactService
      .getZipAndExplode(configLoader.cfg.githubSecrets.personalAccessToken, archiveUrl, branch)

    try {
      val regexMatchingEngine = if (isPrivate) privateMatchingEngine else publicMatchingEngine
      val results             = regexMatchingEngine.run(explodedZipDir)
      val report              = Report.create(repository, repositoryUrl, commitId, authorName, branch, results)
      for {
        _ <- reportsRepository.saveReport(report)
        _ <- alertingService.alert(report)
      } yield {
        report
      }
    } finally {
      FileUtils.deleteDirectory(explodedZipDir)
    }
  }

  def scanCodeBaseFromGit(p: PayloadDetails)(implicit hc: HeaderCarrier): Future[Report] =
    scanRepository(p.repositoryName, p.branchRef, p.isPrivate, p.repositoryUrl, p.commitId, p.authorName, p.archiveUrl)
}
