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

import com.google.inject.Inject
import org.bson.conversions.Bson
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.leakdetection.model.{Branch, Report, ReportId, Repository}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReportsRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[Report](
  collectionName = "reports",
  mongoComponent = mongoComponent,
  domainFormat   = Report.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("repoName"), IndexOptions().name("repoName-idx").background(true)),
                     IndexModel(Indexes.descending("timestamp"), IndexOptions().name("timestamp-idx").background(true)),
  )
) {

  def saveReport(report: Report): Future[Unit] =
    collection.insertOne(report)
      .toFuture()
      .map(_ => ())

  def updateReport(report: Report): Future[Unit] =
    collection
      .replaceOne(
        filter      = Filters.equal("_id", report.id.value),
        replacement = report
      )
      .toFuture()
      .map { res =>
        if (res.getModifiedCount == 1)
          ()
        else
          throw new Exception(s"Error saving following report in db: $report")
      }

  private val hasLeaks =  Filters.gt("totalLeaks",  0)

  def findLatestReport(repository: Repository, branch: Branch): Future[Option[Report]] =
    collection
      .find(
        filter = Filters.and(
          Filters.equal("repoName", repository.asString),
          Filters.equal("branch", branch.asString)
        )
      )
      .sort(Sorts.descending("timestamp"))
      .headOption()

  def findUnresolvedWithProblems(repository: Repository, branch: Option[Branch] = None): Future[Seq[Report]] =
    collection
      .find(
        filter = Filters.and(
          hasLeaks,
          Filters.equal("repoName", repository.asString),
          branch.fold[Bson](BsonDocument())(b => Filters.equal("branch", b.asString))
        )
      )
      .sort(BsonDocument("timestamp" -> -1))
      .toFuture

  def findByReportId(reportId: ReportId): Future[Option[Report]] =
    collection.find(Filters.eq("_id", reportId.value)).headOption

  def removeAll(): Future[Long] =
    collection
      .deleteMany(filter = BsonDocument())
      .toFuture
      .map(_.getDeletedCount)
}
