/*
 * Copyright 2018 HM Revenue & Customs
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

import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, WordSpec}
import uk.gov.hmrc.leakdetection.scanner.MatchedResult.truncate

class MatchedResultSpec extends WordSpec with Matchers with PropertyChecks {
  implicit val noShrink: Shrink[Int] = Shrink.shrinkAny

  "truncated result" should {
    "have lineText with length up to the configured limit" in {
      forAll(genMatchedResult, Gen.posNum[Int], minSuccessful(500)) {
        case (initialResult, limit) =>
          val truncated       = truncate(initialResult, limit)
          val strippedElipses = truncated.lineText.stripPrefix("[…] ").stripSuffix(" […]").replaceAll(" \\[…\\] ", "")
          strippedElipses.length should be <= limit
      }
    }

    "contain all matches if their total length is <= limit" in {
      forAll(genMatchedResult, Gen.posNum[Int], minSuccessful(500)) {
        case (initialResult, limit) =>
          val initialTotalLengthOfAllMatches =
            initialResult.matches.map(m => initialResult.lineText.substring(m.start, m.end).length).sum
          if (initialTotalLengthOfAllMatches <= limit) {
            val truncated = truncate(initialResult, limit)
            initialResult.matches.foreach { m =>
              val leakValue = initialResult.lineText.substring(m.start, m.end)
              assert(truncated.lineText.contains(leakValue))
            }
          }
      }
    }

    "don't put […] inbetween consecutive matches" in {
      val initialResult = genMatchedResult.sample.get.copy(
        lineText = "abc XXFF xyz",
        matches  = List(Match(4, 6, "XX"), Match(6, 8, "FF"))
      )

      truncate(initialResult, 4).lineText shouldBe "[…] XXFF […]"
    }

    "contain as many matches as still below limit" in {
      val initialResult = genMatchedResult.sample.get.copy(
        lineText = "abc AA def BB ghi CC xyz",
        matches  = List(Match(4, 6, "AA"), Match(11, 13, "BB"), Match(18, 19, "CC"))
      )

      val limit = "AABB".length

      truncate(initialResult, limit).lineText shouldBe "[…] AA […] BB […]"
    }

    "be idempotent" in {
      forAll(genMatchedResult, Gen.posNum[Int], minSuccessful(500)) {
        case (initialResult, limit) =>
          val truncatedOnce  = truncate(initialResult, limit)
          val truncatedAgain = truncate(truncatedOnce, limit)

          truncatedOnce shouldBe truncatedAgain
      }
    }

    "include a flag if text was truncated (to show info in the UI)" in {
      forAll(genMatchedResult, Gen.posNum[Int], minSuccessful(500)) {
        case (initialResult, limit) =>
          val res = truncate(initialResult, limit)
          if (initialResult.lineText.length > limit) {
            assert(res.isTruncated)
          }
      }
    }
  }

  val genMatchedResult: Gen[MatchedResult] =
    for {
      lineText <- Gen.alphaStr.map(_ + "x")
      matches  <- genConsecutiveMatches(lineText)
    } yield
      MatchedResult(
        scope       = "scope",
        lineText    = lineText,
        lineNumber  = 1,
        ruleId      = "ruleId",
        description = "description",
        matches     = matches)

  def genMatch(lineText: String): Gen[Match] =
    (for {
      start <- Gen.choose(0, lineText.length)
      end   <- Gen.choose(start, lineText.length)
    } yield {
      Match(start, end, lineText.substring(start, end))
    }).retryUntil(m => m.start != m.end)

  def genConsecutiveMatches(lineText: String): Gen[List[Match]] = {
    def keepConsecutive(acc: List[Match], m: Match): List[Match] =
      if (acc.exists(_.end >= m.start)) {
        acc
      } else {
        m :: acc
      }
    Gen
      .listOfN(100, genMatch(lineText))
      .map { matches =>
        matches.sortBy(_.start).foldLeft(List.empty[Match])(keepConsecutive)
      }
  }

}
