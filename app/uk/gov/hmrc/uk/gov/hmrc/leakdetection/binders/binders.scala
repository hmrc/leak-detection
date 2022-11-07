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

package uk.gov.hmrc.leakdetection.binders

import play.api.mvc.{PathBindable, QueryStringBindable}

class SimpleQueryBinder[T](
  bind  : String => Either[String, T],
  unbind: T => String
)(implicit
  stringBinder: QueryStringBindable[String]
) extends QueryStringBindable[T] {

  override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, T]] =
    for {
      value <- stringBinder.bind(key, params)
    } yield {
      value match {
        case Right(s) => bind(s)
        case _        => Left(s"Unable to bind")
      }
    }

  override def unbind(key: String, value: T): String =
    stringBinder.unbind(key, unbind(value))
}


class SimpleObjectBinder[T](
  bind  : String => T,
  unbind: T => String
)(implicit
  m: Manifest[T]
) extends PathBindable[T] {
  override def bind(key: String, value: String): Either[String, T] =
    try Right(bind(value))
    catch {
      case e: Throwable =>
        Left(s"Cannot parse parameter '$key' with value '$value' as '${m.runtimeClass.getSimpleName}'")
    }

  def unbind(key: String, value: T): String = unbind(value)
}
