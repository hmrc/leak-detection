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

import com.google.inject.Inject
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.ActiveBranchesRepository

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class ActiveBranchesService @Inject()(activeBranchesRepository: ActiveBranchesRepository)(
  implicit ec: ExecutionContext) {

  def getActiveBranches(repoName: Option[String], includeAllRepos: Boolean): Future[Seq[ActiveBranch]] = {
    (repoName, includeAllRepos) match {
      case (Some(r), _) => activeBranchesRepository.findForRepo(r)
      case (None, true) => activeBranchesRepository.findAll()
      case (None, false) => Future.successful(Seq.empty)
    }
  }

  def markAsActive(repository: Repository, branch: Branch, reportId: ReportId): Future[Unit] =
    activeBranchesRepository
      .find(repository.asString, branch.asString)
      .map(_.map(a => activeBranchesRepository.update(a.copy(updated = Instant.now(), reportId = reportId.value)))
        .getOrElse(activeBranchesRepository.create(ActiveBranch(repository.asString, branch.asString, reportId.value))))

  def clearAfterBranchDeleted(deleteBranchEvent: DeleteBranchEvent): Future[Unit] =
    activeBranchesRepository.delete(deleteBranchEvent.repositoryName, deleteBranchEvent.branchRef)
}
