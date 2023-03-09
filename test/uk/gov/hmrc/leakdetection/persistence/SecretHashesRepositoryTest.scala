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

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.model.SecretHash
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import scala.concurrent.ExecutionContext.Implicits.global

class SecretHashesRepositoryTest extends AnyWordSpec
  with ScalaFutures
  with IntegrationPatience
  with Matchers
  with CleanMongoCollectionSupport
  with PlayMongoRepositorySupport[SecretHash]
   {

  val repository = new SecretHashesRepository(mongoComponent)
  val knownHashes = Seq(
   SecretHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"), //"hello"
   SecretHash("486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7"), //"world"
   SecretHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")  //"test"
  )

   val testHashes = Map(
     "foo"   -> "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
     "bar"   -> "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9",
     "hello" -> "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
     "world" -> "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7"
   )

  "insertHashes" should {

    "insert hashes into the mongo collection" in {
      repository.insertHashes(knownHashes)

      val res = repository.collection.find().toFuture()
      res.futureValue.length shouldBe 3
      res.futureValue should contain theSameElementsAs knownHashes
    }
  }

   "isKnownSecretHash" should {
     "only return the hashes that exist in our hashes collection" in {
       repository.insertHashes(knownHashes)

       val res = repository.isKnownSecretHash(testHashes.values.toList).futureValue
       res.length shouldBe 2
       res should contain theSameElementsAs List(testHashes("hello"), testHashes("world"))
     }

     "return an empty list if no matches" in {
       repository.insertHashes(knownHashes)
       val res = repository.isKnownSecretHash(List(testHashes("foo"), testHashes("bar"))).futureValue

       res.length shouldBe 0
       res shouldBe List.empty

     }

     "return an empty list if empty list is provided as an input" in {
       repository.insertHashes(knownHashes)
       val res = repository.isKnownSecretHash(List()).futureValue

       res.length shouldBe 0
       res shouldBe List.empty

     }

   }
}
