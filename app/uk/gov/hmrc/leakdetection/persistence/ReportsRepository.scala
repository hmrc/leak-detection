/*
 * Copyright 2020 HM Revenue & Customs
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
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, ReadConcern, ReadPreference}
import reactivemongo.play.json.ImplicitBSONHandlers
import uk.gov.hmrc.leakdetection.model.{Report, ReportId}
import uk.gov.hmrc.mongo.ReactiveRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReportsRepository @Inject()(reactiveMongoComponent: ReactiveMongoComponent)(implicit ec: ExecutionContext)
    extends ReactiveRepository[Report, ReportId](
      collectionName = "reports",
      mongo          = reactiveMongoComponent.mongoConnector.db,
      domainFormat   = Report.mongoFormat,
      idFormat       = ReportId.format
    ) {

  import ImplicitBSONHandlers._

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    Future.sequence(
      Seq(
        idx("repoName", IndexType.Hashed),
        idx("timestamp", IndexType.Descending)
      ))

  private def idx(field: String, indexType: IndexType) =
    collection.indexesManager
      .ensure(Index(key = Seq(field -> indexType), name = Some(s"$field-idx"), background = true))

  def saveReport(report: Report): Future[Unit] =
    insert(report).map { writeResult =>
      val savedSuccessfully = writeResult.ok && writeResult.n == 1
      if (savedSuccessfully) {
        ()
      } else {
        throw new Exception(s"Error saving following report in db: $report")
      }
    }

  def updateReport(report: Report): Future[Unit] =
    collection
      .update(ordered = false)
      .one(
        _id(report._id),
        Report.mongoFormat.writes(report),
        upsert = false
      )
      .map { res =>
        val updatedSuccessfully = res.ok && res.nModified == 1
        if (updatedSuccessfully) {
          ()
        } else {
          throw new Exception(s"Error saving following report in db: $report")
        }
      }

  private val hasUnresolvedErrorsSelector =
    Json.obj(
      "inspectionResults" -> Json.obj("$gt" -> JsArray()),
      "leakResolution"    -> JsNull
    )

  def findUnresolvedWithProblems(repoName: String, branch: Option[String] = None): Future[List[Report]] =
    collection
      .find[JsObject, JsObject](
        hasUnresolvedErrorsSelector ++
          Json.obj("repoName" -> repoName) ++
          branch.fold(Json.obj())(b => Json.obj("branch" -> b)),
        None
      )
      .sort(Json.obj("timestamp" -> -1))
      .cursor[Report](ReadPreference.primaryPreferred)
      .collect[List](maxDocs = -1, err = Cursor.FailOnError[List[Report]]())

  def findByReportId(reportId: ReportId): Future[Option[Report]] =
    findById(reportId)

  def getDistinctRepoNames: Future[List[String]] =
    collection
      .distinct[String, List](
      key = "repoName",
      selector = Some(hasUnresolvedErrorsSelector),
      readConcern = ReadConcern.Majority,
      collation = None).map(_.sorted)

  def howManyUnresolved(): Future[Int] =
    collection.count(
      selector = Some(Json.obj("inspectionResults" -> Json.obj("$gt" -> JsArray()))),
      limit = None,
      skip = 0,
      hint = None,
      readConcern = ReadConcern.Majority).map(_.intValue())

  def howManyResolved(): Future[Int] =
    collection.count(
      selector = Some(Json.obj("leakResolution" -> Json.obj("$ne" -> JsNull))),
      limit = None,
      skip = 0,
      hint = None,
      readConcern = ReadConcern.Majority).map(_.intValue())

  case class CountByRepo(_id: String, count: Int)

  def howManyUnresolvedByRepository(): Future[Map[String, Int]] = {

    implicit val cr: Reads[CountByRepo]       = Json.reads[CountByRepo]

    import collection.BatchCommands.AggregationFramework.{Group, Match, SumAll}
    import reactivemongo.api.Cursor

    val f = collection.aggregatorContext[CountByRepo](
      Match(Json.obj("inspectionResults" -> Json.obj("$gt" -> JsArray()))),
      List(Group(JsString("$repoName"))( "count" -> SumAll))).
      prepared.cursor.collect[List](-1, Cursor.FailOnError[List[CountByRepo]]()
    )

    f.map(_.map(line => line._id -> line.count).toMap)
  }
}
