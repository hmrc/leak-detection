/*
 * Copyright 2021 HM Revenue & Customs
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

import javax.inject.Singleton
import play.api.libs.json.Reads._
import play.api.libs.json._
import uk.gov.hmrc.leakdetection.model.{Branch, Report, ReportId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import org.mongodb.scala.bson.BsonArray
import com.mongodb.{ReadConcern, ReadPreference}
import org.bson.conversions.Bson
import org.mongodb.scala.bson.BsonDocument
import uk.gov.hmrc.mongo.play.json.Codecs
import org.mongodb.scala.model.{Aggregates, Filters, IndexModel, IndexOptions, Indexes}

import scala.concurrent.{ExecutionContext, Future}
import org.mongodb.scala.model.BsonField

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
                     IndexModel(Indexes.descending("timestamp"), IndexOptions().name("timestamp-idx").background(true))
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

  // TODO no index for inspectionResults or leakResolution (consider a derived boolean `isDefined` field rather than indexing the arrays)
  private val hasUnresolvedErrorsSelector =
    Filters.and(
      Filters.gt("inspectionResults", BsonArray()), // also: {"inspectionResults": {"$gt": {"$size": 0}}}
      Filters.exists("leakResolution", false)
    )

  def findUnresolvedWithProblems(repoName: String, branch: Option[Branch] = None): Future[Seq[Report]] =
    collection
      .withReadPreference(ReadPreference.primary())
      .find(
        filter = Filters.and(
                   hasUnresolvedErrorsSelector,
                   Filters.equal("repoName", repoName),
                   branch.fold[Bson](BsonDocument())(b => Filters.equal("branch", b.name))
                 )
      )
      .sort(BsonDocument("timestamp" -> -1))
      .toFuture

  def findByReportId(reportId: ReportId): Future[Option[Report]] =
    collection.find(Filters.eq("_id", reportId.value)).headOption

  def getDistinctRepoNames: Future[Seq[String]] =
    collection
      .withReadConcern(ReadConcern.MAJORITY)
      .distinct[String](
        fieldName = "repoName",
        filter = hasUnresolvedErrorsSelector
      )
      .toFuture
      .map(_.sorted)

  def howManyUnresolved(): Future[Int] =
    collection
      .withReadConcern(ReadConcern.MAJORITY)
      .countDocuments(
        filter = Filters.gt("inspectionResults", BsonArray()),
      )
      .toFuture
      .map(_.intValue)

  def howManyResolved(): Future[Int] =
    collection
      .withReadConcern(ReadConcern.MAJORITY)
      .countDocuments(
        filter = Filters.exists("leakResolution"),
      )
      .toFuture
      .map(_.intValue())

  case class CountByRepo(_id: String, count: Int)

  def howManyUnresolvedByRepository(): Future[Map[String, Int]] = {
    implicit val cr: Reads[CountByRepo] = Json.reads[CountByRepo]

    collection
      .aggregate[BsonDocument](
        pipeline = Seq[Bson](
                     Aggregates.`match`(Filters.gt("inspectionResults", BsonArray())),
                     Aggregates.group("$repoName", BsonField("count", BsonDocument("$sum" -> 1)))
                   )
      )
      .toFuture
     .map(
       _.map { doc =>
        val line = Codecs.fromBson[CountByRepo](doc)
        line._id -> line.count
       }.toMap
     )
  }

  def countAll(): Future[Int] =
    collection.countDocuments().toFuture.map(_.toInt)

  def removeAll(): Future[Long] =
    collection
      .deleteMany(filter = BsonDocument())
      .toFuture
      .map(_.getDeletedCount)
}
