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

import org.scalatest.Inspectors
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.{Configuration, Environment}
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.PushUpdate
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global

class GithubRequestsQueueRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[WorkItem[PushUpdate]]
     with ScalaFutures
     with Inspectors
     with IntegrationPatience {

  val anInstant: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  override lazy val repository =
    new GithubRequestsQueueRepository(Configuration.load(Environment.simple()), mongoComponent) {
      override def now(): Instant = anInstant
    }

  "The github request queue repository" should {
    "ensure indexes are created" in {
      repository.collection.listIndexes().toFuture().futureValue.size shouldBe 4
    }

    "be able to save and reload a github request" in {
      val pushUpdate = aPushUpdate
      val workItem   = repository.pushNew(pushUpdate, anInstant).futureValue

      repository.findById(workItem.id).futureValue.get should have(
        'item (pushUpdate),
        'status (ProcessingStatus.ToDo),
        'receivedAt (anInstant),
        'updatedAt (anInstant)
      )
    }

    "be able to save the same requests twice" in {
      val pushUpdate = aPushUpdate
      repository.pushNew(pushUpdate, anInstant).futureValue
      repository.pushNew(pushUpdate, anInstant).futureValue

      val requests = repository.collection.find().toFuture().futureValue
      requests should have(size(2))

      every(requests) should have(
        'item (pushUpdate),
        'status (ProcessingStatus.ToDo),
        'receivedAt (anInstant),
        'updatedAt (anInstant)
      )
    }
  }
}
