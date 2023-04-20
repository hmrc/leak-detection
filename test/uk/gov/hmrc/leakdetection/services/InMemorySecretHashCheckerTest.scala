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

package uk.gov.hmrc.leakdetection.services

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.must.Matchers.{contain, convertToAnyMustWrapper}
import org.scalatest.wordspec.AnyWordSpec

class InMemorySecretHashCheckerTest extends AnyWordSpec {

  val knownHashes = Map(
    "hello" -> "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    "world" -> "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7",
    "test"  -> "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
  )

  val testHashes = Map(
    "foo"   -> "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
    "bar"   -> "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9",
    "hello" -> "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    "world" -> "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7"
  )

  val memorySecretHashChecker = new InMemorySecretHashChecker(knownHashes = knownHashes.values.toSet)

  "check" should {
    "only return hashes that exist in our knownHashes set" in {
      val res = memorySecretHashChecker.check(testHashes.values.toList).futureValue
      res must contain theSameElementsAs List(knownHashes("hello"), knownHashes("world"))
    }

    "return an empty list if no matches" in {
      val res = memorySecretHashChecker.check(List(testHashes("foo"), testHashes("bar"))).futureValue
      res mustBe List.empty
    }

    "return an empty list if empty list provided as input" in {
      val res = memorySecretHashChecker.check(List()).futureValue
      res mustBe List.empty
    }
  }


}
