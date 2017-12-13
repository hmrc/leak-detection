/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection

import play.api.libs.json.{JsValue, Json, Writes}
import scala.util.Random
import uk.gov.hmrc.leakdetection.model.{PayloadDetails, Report}
import uk.gov.hmrc.leakdetection.scanner.{Match, MatchedResult, Result}

object ModelFactory {

  def aString(s: String = ""): String =
    s + "_" + Random.alphanumeric.take(10).mkString

  def aPositiveInt: Int = Random.nextInt(Int.MaxValue)

  def few[T](f: () => T): List[T] =
    List.fill(Random.nextInt(5) + 1)(f())

  def maybe[T](t: T): Option[T] =
    if (aBoolean) Some(t) else None

  def aBoolean: Boolean = Random.nextBoolean()

  def aPayloadDetails =
    PayloadDetails(
      repositoryName = aString("repositoryName"),
      isPrivate      = aBoolean,
      authorName     = aString("author"),
      branchRef      = aString("ref"),
      repositoryUrl  = aString("repo"),
      commitId       = aString("commitId"),
      archiveUrl     = aString("archiveUrl")
    )

  def aMatchedResult =
    MatchedResult(
      lineText    = aString("lineText"),
      lineNumber  = aPositiveInt,
      ruleId      = aString("ruleId"),
      description = aString("description"),
      matches     = List(Match(10, 14, "line"))
    )

  def aResult = Result(
    filePath    = aString("file-path"),
    scanResults = aMatchedResult
  )

  def aReport: Report =
    Report.create(
      repositoryName = aString("repositoryName"),
      repositoryUrl  = aString("repo"),
      commitId       = aString("commitId"),
      authorName     = aString("author"),
      branch         = aString("ref"),
      results        = few(() => aResult)
    )

  implicit val payloadDetailsWrites: Writes[PayloadDetails] =
    new Writes[PayloadDetails] {
      def writes(pd: PayloadDetails): JsValue = {
        import pd._
        Json.obj(
          "ref"   -> branchRef,
          "after" -> commitId,
          "repository" -> Json
            .obj("name" -> repositoryName, "url" -> repositoryUrl, "archive_url" -> archiveUrl, "private" -> isPrivate),
          "pusher" -> Json.obj("name" -> authorName)
        )
      }
    }

  def asJson[A: Writes](a: A): String =
    Json.stringify(Json.toJson(a))

}
