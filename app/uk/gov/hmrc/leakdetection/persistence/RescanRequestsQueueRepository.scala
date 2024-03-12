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

import org.mongodb.scala.model.Filters
import play.api.Configuration
import uk.gov.hmrc.leakdetection.model.PushUpdate
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}

import java.time.{Duration, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class RescanRequestsQueueRepository @Inject()(
  configuration: Configuration,
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends WorkItemRepository[PushUpdate](
  collectionName = "rescanRequestsQueue",
  mongoComponent = mongoComponent,
  itemFormat     = MongoPushUpdateFormats.formats,
  workItemFields = WorkItemFields.default
) {
  override def now(): Instant =
    Instant.now()

  lazy val retryIntervalMillis =
    configuration.getMillis("queue.retryAfter")

  override val inProgressRetryAfter: Duration =
    Duration.ofMillis(retryIntervalMillis)

  def pullOutstanding: Future[Option[WorkItem[PushUpdate]]] =
    super.pullOutstanding(
      failedBefore    = now().minusMillis(retryIntervalMillis.toInt),
      availableBefore = now()
    )

  def delete(repositoryName: String, branchRef: String): Future[Unit] =
    collection
      .deleteMany(
        filter = Filters.and(
          Filters.equal("item.repositoryName", repositoryName),
          Filters.equal("item.branchRef", branchRef)
        )
      )
      .toFuture()
      .map(_ => ())
}
