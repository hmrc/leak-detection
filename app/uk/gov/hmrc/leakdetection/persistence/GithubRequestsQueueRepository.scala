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

import javax.inject.{Inject, Singleton}

import org.joda.time.{DateTime, Duration}
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.workitem._

import scala.concurrent.{ExecutionContext, Future}

object MongoPayloadDetailsFormats {
  implicit val pf = Json.format[PayloadDetails]
  val formats     = WorkItem.workItemMongoFormat[PayloadDetails]
}

@Singleton
class GithubRequestsQueueRepository @Inject()(
  configuration: Configuration,
  reactiveMongoComponent: ReactiveMongoComponent)
    extends WorkItemRepository[PayloadDetails, BSONObjectID](
      collectionName = "githubRequestsQueue",
      mongo = reactiveMongoComponent.mongoConnector.db,
      itemFormat = MongoPayloadDetailsFormats.formats,
      config = configuration.underlying
    ) {
  override def now: DateTime = DateTimeUtils.now

  override lazy val workItemFields: WorkItemFieldNames = new WorkItemFieldNames {
    val receivedAt   = "receivedAt"
    val updatedAt    = "updatedAt"
    val availableAt  = "receivedAt"
    val status       = "status"
    val id           = "_id"
    val failureCount = "failureCount"
  }

  val failureRetryAfterProperty: String    = "queue.retryAfter"
  val inProgressRetryAfterProperty: String = failureRetryAfterProperty

  lazy val retryIntervalMillis: Long = configuration.getMillis(failureRetryAfterProperty)

  override lazy val inProgressRetryAfter: Duration = Duration.millis(retryIntervalMillis)

  def pullOutstanding(implicit ec: ExecutionContext): Future[Option[WorkItem[PayloadDetails]]] =
    super.pullOutstanding(now.minusMillis(retryIntervalMillis.toInt), now)

  def complete(id: BSONObjectID)(implicit ec: ExecutionContext): Future[Boolean] = {
    val selector = JsObject(
      Seq("_id" -> Json.toJson(id)(ReactiveMongoFormats.objectIdFormats), "status" -> Json.toJson(InProgress)))
    collection.delete().one(selector).map(_.n > 0)
  }
}
