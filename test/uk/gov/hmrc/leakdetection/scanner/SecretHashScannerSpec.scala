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

package uk.gov.hmrc.leakdetection.scanner

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.leakdetection.config.SecretHashConfig
import uk.gov.hmrc.leakdetection.services.InMemorySecretHashChecker

import scala.concurrent.ExecutionContext.Implicits.global

class SecretHashScannerSpec extends AnyWordSpec
  with ScalaFutures
  with IntegrationPatience
  with Matchers {

  //Mock unhashed decrypted secrets
  val knownSecrets = Set("HELLOWORLD", "TESTSECRET")

  val knownHashes = Set(
    "0b21b7db59cd154904fac6336fa7d2be1bab38d632794f281549584068cdcb74",
    "f6d81236a7a7717a1fe6bf50a69d8fe72cb0501836e516d59e311304859e696a"
  )

  val inMemorySecretHashChecker = new InMemorySecretHashChecker(knownHashes)

  "shouldScan" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(15), inMemorySecretHashChecker)
    "return true if the word is greater than the min word length" in {
      hashScanner.shouldScan("1234567890123456") shouldBe true
    }

    "return true if the word is equal to the min word length" in {
      hashScanner.shouldScan("123456789012345") shouldBe true
    }

    "return false if the word is less than the min word length" in {
      hashScanner.shouldScan("12345678901234") shouldBe false
    }
  }

  "splitLine" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(15), inMemorySecretHashChecker)
    "split words by a single whitespace" in {
      hashScanner.splitLine("Here are some words") should contain theSameElementsInOrderAs  Seq("Here", "are", "some", "words")
    }

    "split words seperated by varying amounts of whitespace" in {
      hashScanner.splitLine("Here  are\tsome   words") should contain theSameElementsInOrderAs  Seq("Here", "are", "some", "words")
    }

    "return an empty sequence when given a blank string" in {
      hashScanner.splitLine(" ") shouldBe empty
    }

    "return an empty sequence when given an empty string" in {
      hashScanner.splitLine("") shouldBe empty
    }
  }

  "hash" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(15), inMemorySecretHashChecker)
    "create a SHA256 hash of a given string" in {
      hashScanner.hash("HELLOWORLD") shouldBe "0b21b7db59cd154904fac6336fa7d2be1bab38d632794f281549584068cdcb74"
    }
  }

  "scanLine" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(5), inMemorySecretHashChecker)
    "return a matched result if the line contains a known secret" in {
      val res = hashScanner.scanLine("THIS CONTAINS A SECRET HELLOWORLD GOODBYE", 3, "/hello.txt", false, Seq.empty).futureValue
      res.nonEmpty shouldBe true
    }

    "return none if the line does not contain a known secret" in {
      val res = hashScanner.scanLine("THIS CONTAINS NO SECRET", 3, "/hello.txt", false, Seq.empty).futureValue
      res.nonEmpty shouldBe false
    }

    "return a MatchedResult with the lineText attribute containing the redacted secret" in {
      val res = hashScanner.scanLine("THIS CONTAINS A SECRET HELLOWORLD GOODBYE", 3, "/hello.txt", false, Seq.empty).futureValue
      res.head.lineText shouldBe "THIS CONTAINS A SECRET ********** GOODBYE"
    }

    "return a MatchedResult with the start and end index of the secret identified" in {
      val res = hashScanner.scanLine("THIS CONTAINS A SECRET HELLOWORLD GOODBYE", 3, "/hello.txt", false, Seq.empty).futureValue
      res.head.matches.head shouldBe Match(23,33)
    }
  }

  "containsSecrets" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(5), inMemorySecretHashChecker)
    "return a list of words, who's hashes are stored within the known values database" in {
      val res = hashScanner.containsSecrets(words = List("HELLOWORLD", "THIS", ",", "TESTSECRET", "hey", "helloworld")).futureValue
      res should contain theSameElementsAs List("HELLOWORLD", "TESTSECRET")
    }

    "return an empty List when there are no matches" in {
      val res = hashScanner.containsSecrets(words = List("THIS", ",", "hey", "helloworld")).futureValue
      res shouldBe List.empty
    }
  }
}
