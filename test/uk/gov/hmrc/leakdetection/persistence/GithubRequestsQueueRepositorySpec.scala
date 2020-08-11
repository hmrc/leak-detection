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

import com.typesafe.config.ConfigFactory
import org.joda.time.{DateTime, Duration}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, Inspectors, LoneElement}
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.ReadPreference
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.workitem.WorkItem
import scala.concurrent.ExecutionContext.Implicits.global

class GithubRequestsQueueRepositorySpec
    extends UnitSpec
    with MongoSpecSupport
    with BeforeAndAfterEach
    with ScalaFutures
    with Inspectors
    with LoneElement
    with IntegrationPatience {

  val anInstant = DateTimeUtils.now

  def repoAtInstant(anInstant: DateTime): GithubRequestsQueueRepository =
    new GithubRequestsQueueRepository(Configuration(ConfigFactory.empty), new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }) {
      override lazy val inProgressRetryAfter: Duration = Duration.standardHours(1)
      override lazy val retryIntervalMillis: Long      = 10000L
      override def now: DateTime                       = anInstant
    }

  lazy val repo = repoAtInstant(anInstant)

  override protected def beforeEach(): Unit = {
    await(repo.drop)
    await(repo.ensureIndexes)
  }

  "The github request queue repository" should {

    "ensure indexes are created" in {
      repo.collection.indexesManager.list().futureValue.size shouldBe 4
    }

    "be able to save and reload a github request" in {
      val payloadDetails = aPayloadDetails
      val workItem       = repo.pushNew(payloadDetails, anInstant).futureValue

      repo.findById(workItem.id).futureValue.get should have(
        'item (payloadDetails),
        'status (uk.gov.hmrc.workitem.ToDo),
        'receivedAt (anInstant),
        'updatedAt (anInstant)
      )
    }

    "be able to save the same requests twice" in {
      val payloadDetails = aPayloadDetails
      repo.pushNew(payloadDetails, anInstant).futureValue
      repo.pushNew(payloadDetails, anInstant).futureValue

      val requests = repo.findAll(ReadPreference.primaryPreferred).futureValue
      requests should have(size(2))

      every(requests) should have(
        'item (payloadDetails),
        'status (uk.gov.hmrc.workitem.ToDo),
        'receivedAt (anInstant),
        'updatedAt (anInstant)
      )
    }

    "pull ToDo github requests" in {
      val payloadDetails = aPayloadDetails
      repo.pushNew(payloadDetails, anInstant).futureValue

      val repoLater: GithubRequestsQueueRepository = repoAtInstant(anInstant.plusMillis(1))

      repoLater.pullOutstanding.futureValue.get should have(
        'item (payloadDetails),
        'status (uk.gov.hmrc.workitem.InProgress)
      )
    }

    "pull nothing if no github requests exist" in {
      repo.pullOutstanding.futureValue should be(None)
    }

    "not pull github requests failed after the failedBefore time" in {
      val workItem: WorkItem[PayloadDetails] = repo.pushNew(aPayloadDetails, anInstant).futureValue
      repo.markAs(workItem.id, uk.gov.hmrc.workitem.Failed).futureValue should be(true)

      repo.pullOutstanding.futureValue should be(None)
    }

    "complete and delete a github requests if it is in progress" in {
      //given
      val workItem = repo.pushNew(aPayloadDetails, anInstant).futureValue
      repo.markAs(workItem.id, uk.gov.hmrc.workitem.InProgress).futureValue should be(true)

      //when
      repo.complete(workItem.id).futureValue should be(true)

      //then
      repo.findById(workItem.id).futureValue shouldBe None
    }

    "not complete a github requests if it is not in progress" in {
      //given
      val workItem = repo.pushNew(aPayloadDetails, anInstant).futureValue

      //when
      repo.complete(workItem.id).futureValue should be(false)

      //then
      repo.findById(workItem.id).futureValue shouldBe Some(workItem)
    }

    "not complete a github requests if it cannot be found" in {
      repo.complete(BSONObjectID.generate).futureValue should be(false)
    }
  }

}
