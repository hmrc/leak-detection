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

import java.time.{Duration, Instant}
import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.Json
import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.workitem._

import scala.concurrent.{ExecutionContext, Future}

object MongoPayloadDetailsFormats {
  val workItemFieldNames = WorkItemFieldNames.default

  val formats = {
    implicit val pf = Json.format[PayloadDetails]
    import uk.gov.hmrc.mongo.play.json.formats.MongoFormats.Implicits.objectIdFormats
    import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.Implicits.jatInstantFormats
    WorkItem.formatForFields[PayloadDetails](workItemFieldNames)
  }
}

@Singleton
class GithubRequestsQueueRepository @Inject()(
  configuration: Configuration,
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends WorkItemRepository[PayloadDetails, ObjectId](
  collectionName = "githubRequestsQueue",
  mongoComponent = mongoComponent,
  itemFormat     = MongoPayloadDetailsFormats.formats,
  workItemFields = MongoPayloadDetailsFormats.workItemFieldNames
) {
  override def now(): Instant =
    Instant.now

  lazy val retryIntervalMillis =
    configuration.getMillis("queue.retryAfter")

  override val inProgressRetryAfter: Duration =
    Duration.ofMillis(retryIntervalMillis)

  def pullOutstanding: Future[Option[WorkItem[PayloadDetails]]] =
    super.pullOutstanding(
      failedBefore    = now.minusMillis(retryIntervalMillis.toInt),
      availableBefore = now
    )

  // TODO add a completeAndDelete function to work-item-repo
  def complete(id: ObjectId): Future[Boolean] =
    collection.deleteOne(
      Filters.and(
        Filters.equal("_id", id),
        Filters.equal("status", ProcessingStatus.toBson(ProcessingStatus.InProgress))
      )
    ).toFuture
     .map(_.getDeletedCount > 0)
}
