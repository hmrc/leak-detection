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

package uk.gov.hmrc.leakdetection.controllers

import play.api.libs.json.{JsValue, Json, Writes}
import scala.util.Random
import uk.gov.hmrc.leakdetection.model.{Author, PayloadDetails}

object ModelFactory {

  def aString(s: String = ""): String =
    s + "_" + Random.alphanumeric.take(10).mkString

  def few[T](f: () => T): List[T] =
    List.fill(Random.nextInt(5))(f())

  def anAuthor =
    Author(
      name     = aString("author"),
      email    = aString("email"),
      username = aString("username")
    )

  def aBoolean: Boolean = Random.nextBoolean()

  def aPayloadDetails =
    PayloadDetails(
      repositoryName = aString("repositoryName"),
      isPrivate      = aBoolean,
      authors        = few(() => anAuthor),
      branchRef      = aString("ref"),
      repositoryUrl  = aString("repo"),
      commitId       = aString("commitId"),
      archiveUrl     = aString("archiveUrl")
    )

  implicit val payloadDetailsWrites: Writes[PayloadDetails] =
    new Writes[PayloadDetails] {
      def writes(pd: PayloadDetails): JsValue = {
        import pd._
        Json.obj(
          "ref"   -> branchRef,
          "after" -> commitId,
          "repository" -> Json.obj(
            "name"        -> repositoryName,
            "url"         -> repositoryUrl,
            "archive_url" -> archiveUrl,
            "private"     -> isPrivate),
          "commits" -> authors.map { a =>
            Json.obj(
              "author" -> Json.obj(
                "name"     -> a.name,
                "email"    -> a.email,
                "username" -> a.username
              ))
          }
        )
      }
    }

  def jsonify[A: Writes](a: A): String =
    Json.stringify(Json.toJson(a))

}
