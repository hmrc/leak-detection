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

import play.shaded.oauth.org.apache.commons.codec.binary.Hex
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption, SecretHashConfig}
import uk.gov.hmrc.leakdetection.services.SecretHashChecker

import java.security.MessageDigest
import scala.concurrent.{ExecutionContext, Future}

class SecretHashScanner(
   secretHashConfig: SecretHashConfig,
   secretHashChecker: SecretHashChecker)
                       (implicit ec: ExecutionContext)
{

  private val md = MessageDigest.getInstance("SHA-256")

  def scanLine(
                line                    : String,
                lineNumber              : Int,
                filePath                : String,
                inlineExemption         : Boolean,
                serviceDefinedExemptions: Seq[RuleExemption]
              ): Future[List[MatchedResult]] = {

    for {
      wordsToScan    <- Future.successful(splitLine(line).filter(shouldScan))
      leaksFound     <- containsSecrets(wordsToScan.toList)
      matchedResults = leaksFound.map(word =>
        MatchedResult(
          filePath = filePath,
          scope = Rule.Scope.FILE_CONTENT,
          lineText = line.replace(word, "*" * word.length),
          lineNumber = lineNumber,
          ruleId = "hashed_secret",
          description = "Comparison against a known list of hashed secrets",
          matches = List(Match(line.indexOf(word), line.indexOf(word) + word.length)),
          priority = Rule.Priority.High
        )
      )
    } yield matchedResults

    //To do:
    //Now that we return futures, would be easier if we pass all our words at once into isSecret, and check them, rather than one at a time.
    //This makes life easier with future traversal.
  }
  //Pass in string, check to see if string contains a match of known hashes

  def shouldScan(word: String): Boolean =
    word.length >= secretHashConfig.minWordSize

  def splitLine(line: String): Seq[String] = {
    if(line == "")
      Seq.empty
    else
      line.split("\\s+")
  }

  def hash(str: String): String = {
    md.reset()
    Hex.encodeHex(md.digest(str.getBytes)).mkString
  }

  def containsSecrets(words: List[String]): Future[List[String]] = {
    val hashToWord: Map[String, String] = words.map(word => hash(word) -> word).toMap
    val knownSecrets = secretHashChecker.check(hashToWord.keys.toList)

    for {
      secretHashes <- knownSecrets
    } yield secretHashes.map(hash => hashToWord(hash))

  }
}

object SecretHashScanner {

}
