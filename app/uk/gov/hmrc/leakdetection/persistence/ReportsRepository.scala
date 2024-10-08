/*
 * Copyright 2023 HM Revenue & Customs
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
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, Sorts}
import uk.gov.hmrc.leakdetection.model.{Branch, Report, ReportId, Repository}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReportsRepository @Inject()(
  mongoComponent: MongoComponent
)(using ExecutionContext
) extends PlayMongoRepository[Report](
  collectionName = "reports",
  mongoComponent = mongoComponent,
  domainFormat   = Report.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("repoName"), IndexOptions().name("repoName-idx").background(true)),
                     IndexModel(Indexes.descending("timestamp"), IndexOptions().name("timestamp-idx").background(true)),
                     IndexModel(Indexes.compoundIndex(Indexes.hashed("commitId"), Indexes.ascending("branchRef")), IndexOptions().name("commitId-branch-idx").background(true))
                   )
):

  override lazy val requiresTtlIndex: Boolean = false
  private val               hasLeaks: Bson    =  Filters.gt("totalLeaks",  0)

  def saveReport(report: Report): Future[Unit] =
    collection
      .insertOne(report)
      .toFuture()
      .map(_ => ())

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
      .sort(Sorts.descending("timestamp"))
      .toFuture()

  def findByReportId(reportId: ReportId): Future[Option[Report]] =
    collection
      .find(Filters.eq("_id", reportId.value))
      .headOption()

  def findByCommitIdAndBranch(commitId: String, branch: String): Future[Option[Report]] =
    collection
      .find(filter = Filters.and(
        Filters.eq("commitId", commitId),
        Filters.eq("branch", branch))
      )
      .headOption()

  def removeAll(): Future[Long] =
    collection
      .deleteMany(filter = BsonDocument())
      .toFuture()
      .map(_.getDeletedCount)
