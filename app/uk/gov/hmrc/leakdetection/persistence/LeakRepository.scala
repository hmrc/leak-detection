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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.and
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import org.mongodb.scala.model.{Aggregates, BsonField, Filters, IndexModel, IndexOptions, Indexes, Projections}

import play.api.Logging
import play.api.libs.json.{Json, Reads}
import uk.gov.hmrc.leakdetection.model.{Branch, Leak, LeakUpdateResult, ReportId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LeakRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext) extends PlayMongoRepository[Leak](
  collectionName = "leaks",
  mongoComponent = mongoComponent,
  domainFormat = Leak.mongoFormat,
  indexes = Seq(
    IndexModel(Indexes.descending("repoName"), IndexOptions().name("repoName-idx").background(true)),
    IndexModel(Indexes.descending("ruleId"), IndexOptions().name("ruleId-idx").background(true)),
    IndexModel(Indexes.descending("reportId"), IndexOptions().name("reportId-idx").background(true)),
    IndexModel(Indexes.descending("timestamp"), IndexOptions().name("timestamp-idx").background(true)))) with Logging {

  // TODO: use transactions
  def update(repo: String, branch: String, violations: Seq[Leak]): Future[LeakUpdateResult] =
    for {
      // remove previous violations
      deleted <- removeBranch(repo, branch)
      // replace with new ones
      inserted <- if (violations.nonEmpty) collection.insertMany(violations).toFuture().map(_.getInsertedIds.size()) else Future(0)
      _ = logger.info(s"removed ${deleted} leaks, added ${inserted} leaks for $repo/$branch")
    } yield LeakUpdateResult(inserted, deleted)

  def removeBranch(repo: String, branch: String): Future[Long] =
    collection.deleteMany(filter = and(Filters.eq("repoName", repo), Filters.eq("branch", branch))).toFuture().map(_.getDeletedCount)

  def findLeaksBy(ruleId:   Option[String] = None,
                  repoName: Option[String] = None,
                  branch:   Option[String] = None
                 ): Future[Seq[Leak]] = {

    val ruleFilter:   Option[Bson] = ruleId.map(Filters.eq("ruleId", _))
    val repoFilter:   Option[Bson] = repoName.map(Filters.eq("repoName", _))
    val branchFilter: Option[Bson] = repoName.flatMap( _ => branch.map(Filters.eq("branch", _))) // only active when repoName is also set

    (Seq(ruleFilter, repoFilter, branchFilter).flatten match {
      case Nil => collection.find()
      case f   => collection.find(Filters.and(f: _*))
    }).toFuture()
  }

  def findLeaksForReport(reportId: String): Future[Seq[Leak]] =
    collection.find(filter = Filters.eq("reportId", reportId)).toFuture()

  def findLeaksForRepository(repo: String, branch: String): Future[Seq[Leak]] =
    collection.find(
      Filters.and(
        Filters.eq("repoName", repo),
        Filters.eq("branch", branch))
    ).toFuture()


  def findLeaksForReport(report: ReportId): Future[Seq[Leak]] =
    collection.find(Filters.eq("reportId", report.value)).toFuture()

  def findDistinctRepoNames(): Future[Seq[String]] =
    collection.distinct[String]("repoName").toFuture()

  def countAll(): Future[Int] = collection.countDocuments().toFuture().map(_.toInt)

  def countByRepo(): Future[Map[String, Int]] = collection.aggregate[BsonDocument](
      pipeline = Seq[Bson](
        Aggregates.group("$repoName", BsonField("count", BsonDocument("$sum" -> 1)))
      )
    )
    .toFuture.map(_.map { doc =>
        val line = Codecs.fromBson[CountByRepo](doc)(CountByRepo.reads)
        line._id -> line.count
    }.toMap)
}

case class CountByRepo(_id: String, count: Int)

object CountByRepo {
  val reads: Reads[CountByRepo] = Json.reads[CountByRepo]
}