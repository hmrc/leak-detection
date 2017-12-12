/*
 * Copyright 2017 HM Revenue & Customs
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
import play.api.libs.json.{JsArray, Json}
import play.api.libs.json.Reads._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.ReadPreference
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.ImplicitBSONHandlers
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import uk.gov.hmrc.leakdetection.model.{Report, ReportId}
import uk.gov.hmrc.mongo.ReactiveRepository

class ReportsRepository @Inject()(reactiveMongoComponent: ReactiveMongoComponent)(
  implicit ec: ExecutionContext)
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
      .ensure(Index(Seq("field" -> indexType), name = Some(s"$field-idx")))

  def saveReport(report: Report): Future[Report] = insert(report).map { writeResult =>
    if (writeResult.ok && writeResult.n == 1) {
      report
    } else {
      throw new Exception(s"Error saving following report in db: $report")
    }
  }

  def findByRepoName(repoName: String): Future[List[Report]] =
    collection
      .find(Json.obj("repoName" -> repoName))
      .sort(Json.obj("timestamp" -> 1))
      .cursor[Report](ReadPreference.primaryPreferred)
      .collect[List]()

  def findByReportId(reportId: ReportId): Future[Option[Report]] =
    findById(reportId)

  def getDistinctRepoNames: Future[List[String]] =
    collection
      .distinct[String, List](
        "repoName",
        Some(Json.obj("inspectionResults" -> Json.obj("$gt" -> JsArray()))))
      .map(_.sorted)
}
