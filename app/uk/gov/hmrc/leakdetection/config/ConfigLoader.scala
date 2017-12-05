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

package uk.gov.hmrc.leakdetection.config

import javax.inject.Inject
import play.api.Configuration
import pureconfig.syntax._
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}

class ConfigLoader @Inject()(configuration: Configuration) {

  implicit def hint[T]: ProductHint[T] =
    ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  val cfg = configuration.underlying.toOrThrow[Cfg]
}

final case class Cfg(
  allRules: AllRules,
  githubSecrets: GithubSecrets
)

final case class AllRules(
  publicRules: List[Rule],
  privateRules: List[Rule]
)

final case class Rule(
  regex: String,
  description: String
)

final case class GithubSecrets(
  webhookSecretKey: String,
  personalAccessToken: String
)
