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

import org.bson.conversions.Bson
import org.mongodb.scala.model.Filters.and
import org.mongodb.scala.model._
import play.api.Logging
import uk.gov.hmrc.leakdetection.model.{LeakUpdateResult, Warning}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class WarningRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext) extends PlayMongoRepository[Warning](
  collectionName = "warnings",
  mongoComponent = mongoComponent,
  domainFormat = Warning.mongoFormat,
  indexes = Seq(
    IndexModel(Indexes.descending("repoName", "branch"), IndexOptions().name("repoName-branch-idx").background(true)),
    IndexModel(Indexes.descending("reportId"), IndexOptions().name("reportId-idx").background(true)),
    IndexModel(Indexes.descending("timestamp"), IndexOptions().name("timestamp-idx").background(true)))) with Logging {

  def update(repo: String, branch: String, warnings: Seq[Warning]): Future[LeakUpdateResult] =
    for {
      deleted <- removeWarnings(repo, branch)
      inserted <- if (warnings.nonEmpty) collection.insertMany(warnings).toFuture().map(_.getInsertedIds.size()) else Future(0)
      _ = logger.info(s"removed $deleted warnings, added $inserted warnings for $repo/$branch")
    } yield LeakUpdateResult(inserted, deleted)

  private def removeWarnings(repo: String, branch: String): Future[Long] =
    collection.deleteMany(filter = and(Filters.eq("repoName", repo), Filters.eq("branch", branch))).toFuture().map(_.getDeletedCount)

  def findForReport(reportId: String): Future[Seq[Warning]] =
    collection.find(filter = Filters.eq("reportId", reportId)).toFuture()

    def findBy(repoName: Option[String] = None,
               branch: Option[String] = None
              ): Future[Seq[Warning]] = {

      val repoFilter: Option[Bson] = repoName.map(Filters.eq("repoName", _))
      val branchFilter: Option[Bson] = repoName.flatMap(_ => branch.map(Filters.eq("branch", _))) // only active when repoName is also set

      (Seq(repoFilter, branchFilter).flatten match {
        case Nil => collection.find()
        case f => collection.find(Filters.and(f: _*))
      }).toFuture()
    }

}