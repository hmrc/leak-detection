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
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.leakdetection.model.Report
import uk.gov.hmrc.mongo.ReactiveRepository

class ReportsRepository @Inject()(reactiveMongoComponent: ReactiveMongoComponent)(
  implicit ec: ExecutionContext)
    extends ReactiveRepository[Report, BSONObjectID](
      collectionName = "reports",
      mongo          = reactiveMongoComponent.mongoConnector.db,
      domainFormat   = Report.format) {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(
          Index(Seq("commitId" -> IndexType.Hashed), name = Some("commitId-idx")))
      )
    )

  def saveReport(report: Report): Future[Report] = insert(report).map { writeResult =>
    if (writeResult.ok && writeResult.n == 1) {
      report
    } else {
      throw new Exception(s"Error saving following report in db: $report")
    }
  }
}
