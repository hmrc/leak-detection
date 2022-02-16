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
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model._
import uk.gov.hmrc.leakdetection.model.{Report, ReportId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DraftReportsRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[Report](
  collectionName = "draftreports",
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

  private val hasLeaks =  Filters.gt("totalLeaks",  0)

  def findAllWithAnyRuleViolation(): Future[Seq[Report]] =
    collection
      .find(hasLeaks)
      .sort(Sorts.descending("timestamp"))
      .toFuture

  def findAllWithRuleViolation(ruleId: String): Future[Seq[Report]] =
    collection
      .find(Filters.exists(s"rulesViolated.$ruleId"))
      .sort(Sorts.descending("timestamp"))
      .toFuture

  def findByReportId(reportId: ReportId): Future[Option[Report]] =
    collection.find(Filters.eq("_id", reportId.value)).headOption

  def findAllRepositories(): Future[Seq[String]] =
    collection.distinct[String]("repoName").toFuture()

  def removeAll(): Future[Long] =
    collection
      .deleteMany(filter = BsonDocument())
      .toFuture
      .map(_.getDeletedCount)
}
