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
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{PayloadDetails, Report}
import uk.gov.hmrc.leakdetection.persistence.ReportsRepository
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ScanningService @Inject()(
  artifactService: ArtifactService,
  regexMatchingEngine: RegexMatchingEngine,
  configLoader: ConfigLoader,
  reportsRepository: ReportsRepository
) {

  def scanRepository(
    repository: String,
    branch: String,
    isPrivate: Boolean,
    repositoryUrl: String,
    commitId: String,
    authorName: String,
    archiveUrl: String)(implicit ec: ExecutionContext): Future[Report] = {

    val explodedZipDir = artifactService
      .getZipAndExplode(configLoader.cfg.githubSecrets.personalAccessToken, archiveUrl, branch)
    try {
      val rules   = if (isPrivate) configLoader.cfg.allRules.privateRules else configLoader.cfg.allRules.publicRules
      val results = regexMatchingEngine.run(explodedZipDir, rules)
      val report  = Report.create(repository, repositoryUrl, commitId, authorName, branch, results)
      reportsRepository.saveReport(report).map(_ => report)
    } finally {
      FileUtils.deleteDirectory(explodedZipDir)
    }
  }

  def scanCodeBaseFromGit(p: PayloadDetails)(implicit ec: ExecutionContext): Future[Report] =
    scanRepository(p.repositoryName, p.branchRef, p.isPrivate, p.repositoryUrl, p.commitId, p.authorName, p.archiveUrl)

}
