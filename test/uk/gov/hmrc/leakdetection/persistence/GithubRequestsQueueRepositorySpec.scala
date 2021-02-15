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

import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, Inspectors, LoneElement}
import play.api.Configuration
import org.bson.types.ObjectId
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.workitem.{ProcessingStatus, WorkItem}

import scala.concurrent.ExecutionContext.Implicits.global

class GithubRequestsQueueRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DefaultPlayMongoRepositorySupport[WorkItem[PayloadDetails]]
    with BeforeAndAfterEach
    with ScalaFutures
    with Inspectors
    with LoneElement
    with IntegrationPatience {

  val anInstant: Instant = Instant.now()

  def repoAtInstant(anInstant: Instant): GithubRequestsQueueRepository =
    new GithubRequestsQueueRepository(Configuration(ConfigFactory.empty), mongoComponent) {
      override val inProgressRetryAfter: Duration = Duration.ofHours(1)
      override lazy val retryIntervalMillis: Long      = 10000L
      override def now(): Instant                 = anInstant
    }

  override lazy val repository = repoAtInstant(anInstant)

  "The github request queue repository" should {

    "ensure indexes are created" in {
      repository.collection.listIndexes().toFuture.futureValue.size shouldBe 4
    }

    "be able to save and reload a github request" in {
      val payloadDetails = aPayloadDetails
      val workItem       = repository.pushNew(payloadDetails, anInstant).futureValue

      repository.findById(workItem.id).futureValue.get should have(
        'item (payloadDetails),
        'status (ProcessingStatus.ToDo),
        'receivedAt (anInstant),
        'updatedAt (anInstant)
      )
    }

    "be able to save the same requests twice" in {
      val payloadDetails = aPayloadDetails
      repository.pushNew(payloadDetails, anInstant).futureValue
      repository.pushNew(payloadDetails, anInstant).futureValue

      val requests = repository.collection.find().toFuture.futureValue
      requests should have(size(2))

      every(requests) should have(
        'item (payloadDetails),
        'status (ProcessingStatus.ToDo),
        'receivedAt (anInstant),
        'updatedAt (anInstant)
      )
    }

    "pull ToDo github requests" in {
      val payloadDetails = aPayloadDetails
      repository.pushNew(payloadDetails, anInstant).futureValue

      val repoLater: GithubRequestsQueueRepository = repoAtInstant(anInstant.plusMillis(1))

      repoLater.pullOutstanding.futureValue.get should have(
        'item (payloadDetails),
        'status (ProcessingStatus.InProgress)
      )
    }

    "pull nothing if no github requests exist" in {
      repository.pullOutstanding.futureValue should be(None)
    }

    "not pull github requests failed after the failedBefore time" in {
      val workItem: WorkItem[PayloadDetails] = repository.pushNew(aPayloadDetails, anInstant).futureValue
      repository.markAs(workItem.id, ProcessingStatus.Failed).futureValue should be(true)

      repository.pullOutstanding.futureValue should be(None)
    }

    "complete and delete a github requests if it is in progress" in {
      //given
      val workItem = repository.pushNew(aPayloadDetails, anInstant).futureValue
      repository.markAs(workItem.id, ProcessingStatus.InProgress).futureValue should be(true)

      //when
      repository.complete(workItem.id).futureValue should be(true)

      //then
      repository.findById(workItem.id).futureValue shouldBe None
    }

    "not complete a github requests if it is not in progress" in {
      //given
      val workItem = repository.pushNew(aPayloadDetails, anInstant).futureValue

      //when
      repository.complete(workItem.id).futureValue should be(false)

      //then
      repository.findById(workItem.id).futureValue shouldBe Some(workItem)
    }

    "not complete a github requests if it cannot be found" in {
      repository.complete(new ObjectId()).futureValue should be(false)
    }
  }
}
