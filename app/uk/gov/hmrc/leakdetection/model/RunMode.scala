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

package uk.gov.hmrc.leakdetection.model

import play.api.libs.json._
import play.api.mvc.QueryStringBindable

sealed trait RunMode extends Product with Serializable {
  def asString: String
}

object RunMode {
  final case object Normal extends RunMode { override def asString = "normal" }
  final case object Draft extends RunMode { override def asString  = "draft" }

  def parse(s: String): Either[String, RunMode] =
    s match {
      case "normal" => Right(Normal)
      case "draft"  => Right(Draft)
      case rm       => Left(s"Invalid run mode: $rm - should be one of: normal, draft")
    }

  val format: Format[RunMode] =
    new Format[RunMode] {
      override def reads(json: JsValue): JsResult[RunMode] =
        json match {
          case JsString(s) => parse(s).fold(msg => JsError(msg), rm => JsSuccess(rm))
          case _           => JsError("String value expected")
        }

      override def writes(rm: RunMode): JsValue =
        JsString(rm.asString.toLowerCase())
    }

  implicit def queryStringBindable(implicit stringBinder: QueryStringBindable[String]): QueryStringBindable[RunMode] =
    new QueryStringBindable[RunMode] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, RunMode]] =
        for {
          value <- stringBinder.bind(key, params)
        } yield {
          value match {
            case Right(rm) => parse(rm)
            case _         => Left(s"Unable to bind runMode")
          }
        }

      override def unbind(key: String, value: RunMode): String = value.asString
    }
}
