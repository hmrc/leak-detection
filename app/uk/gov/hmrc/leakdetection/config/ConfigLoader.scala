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

package uk.gov.hmrc.leakdetection.config

import com.google.inject.ImplementedBy
import play.api.Configuration
import play.api.libs.json.Json
import pureconfig.syntax._
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}

import javax.inject.Inject

@ImplementedBy(classOf[PlayConfigLoader])
trait ConfigLoader {
  val cfg: Cfg
}

class PlayConfigLoader @Inject()(configuration: Configuration) extends ConfigLoader {

  implicit def hint[T]: ProductHint[T] =
    ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  val cfg = configuration.underlying.toOrThrow[Cfg]
}

final case class Cfg(
  allRules: AllRules,
  githubSecrets: GithubSecrets,
  github: Github,
  maxLineLength: Int,
  clearingCollectionEnabled: Boolean,
  warningMessages: Map[String, String]
)

final case class AllRules(
  publicRules: List[Rule],
  privateRules: List[Rule]
)

final case class Rule(
  id:                String,
  scope:             String,
  regex:             String,
  description:       String,
  ignoredFiles:      List[String] = Nil,
  ignoredExtensions: List[String] = Nil,
  priority:          String       = Rule.Priority.Low,
  draft:             Boolean      = false
)

object Rule {
  implicit val format = Json.format[Rule]
  object Scope {
    val FILE_CONTENT = "fileContent"
    val FILE_NAME    = "fileName"
  }
  object Priority {
    val Low    = "low"
    val Medium = "medium"
    val High   = "high"
  }
}

final case class Github(
  url: String,
  apiUrl: String
)

final case class GithubSecrets(
  personalAccessToken: String,
  webhookSecretKey: String
)

object AllRules {
  implicit val format = Json.format[AllRules]
}

final case class RuleExemption(
  ruleId: String,
  filePaths: Seq[String],
  text: Option[String] = None
)

object RuleExemption {
  implicit val format = Json.format[RuleExemption]
}