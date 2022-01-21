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

import org.mongodb.scala.model.Filters.and
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.leakdetection.model.{Leak, LeakUpdateResult}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LeakRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext) extends PlayMongoRepository[Leak](
  collectionName = "leaks",
  mongoComponent = mongoComponent,
  domainFormat   = Leak.mongoFormat,
  indexes        = Seq(
    IndexModel(Indexes.descending("repoName"), IndexOptions().name("repoName-idx").background(true)),
    IndexModel(Indexes.descending("ruleId"), IndexOptions().name("ruleId-idx").background(true)),
    IndexModel(Indexes.descending("reportId"), IndexOptions().name("reportId-idx").background(true)),
    IndexModel(Indexes.descending("timestamp"), IndexOptions().name("timestamp-idx").background(true)))) with Logging {

  // TODO: use transactions
  def update(repo: String, branch: String, violations: Seq[Leak]): Future[LeakUpdateResult] =
    for {
      // remove previous violations
      deleted  <- removeBranch(repo, branch)
      // replace with new ones
      inserted <- if(violations.nonEmpty) collection.insertMany(violations).toFuture().map(_.getInsertedIds.size()) else Future(0)
      _         = logger.info(s"removed ${deleted} leaks, added ${inserted} leaks for $repo/$branch")
    } yield LeakUpdateResult(inserted, deleted)

  def removeBranch(repo: String, branch:String): Future[Long] =
    collection.deleteMany(filter =  and(Filters.eq("repoName", repo), Filters.eq("branch", branch))).toFuture().map(_.getDeletedCount)

  def findAllLeaks(): Future[Seq[Leak]] =
    collection.find().toFuture()

  def findLeaksForRule(ruleId: String): Future[Seq[Leak]] =
    collection.find(Filters.eq("ruleId", ruleId)).toFuture()

  def findLeaksForRepository(repo: String): Future[Seq[Leak]] =
    collection.find(Filters.eq("repoName", repo)).toFuture()

}
