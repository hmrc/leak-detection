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

package uk.gov.hmrc.leakdetection.persistence

import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.leakdetection.model.ActiveBranch
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ActiveBranchesRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[ActiveBranch](
      collectionName = "activeBranches",
      mongoComponent = mongoComponent,
      domainFormat   = ActiveBranch.mongoFormat,
      indexes        = Seq(
        IndexModel(Indexes.descending("repoName"), IndexOptions().name("repoName-idx").background(true)),
        IndexModel(Indexes.descending("repoName", "branch"), IndexOptions().name("repoName-branch-idx").background(true))
      )
    )
    with Logging {

  def findAll(): Future[Seq[ActiveBranch]] = {
    collection.find().toFuture()
  }

  def findForRepo(repoName: String): Future[Seq[ActiveBranch]] = {
    collection.find(filter = Filters.eq("repoName", repoName)).toFuture()
  }

  def find(repoName: String, branch: String): Future[Option[ActiveBranch]] = {
    collection.find(filter = Filters.and(
      Filters.eq("repoName", repoName),
      Filters.eq("branch", branch))
    ).headOption()
  }

  def create(activeBranch: ActiveBranch): Future[Unit] = {
    collection.insertOne(
      activeBranch
    )
      .toFuture()
      .map(_ => ())
  }

  def update(activeBranch: ActiveBranch): Future[Unit] = {
    collection.replaceOne(
      filter = Filters.and(
        Filters.eq("repoName", activeBranch.repoName),
        Filters.eq("branch", activeBranch.branch)),
      replacement = activeBranch
    )
      .toFuture()
      .map(_ => ())
  }

  def delete(repoName: String, branchName: String): Future[Unit] = {
    collection.deleteOne(filter = Filters.and(
      Filters.eq("repoName", repoName),
      Filters.eq("branch", branchName))
    ).toFuture().map(_ => ())
  }

  def delete(repoName: String): Future[Unit] = {
    collection.deleteOne(filter = Filters.and(
      Filters.eq("repoName", repoName))
    ).toFuture().map(_ => ())
  }

}
