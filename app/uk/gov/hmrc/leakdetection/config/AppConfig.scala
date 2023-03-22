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

package uk.gov.hmrc.leakdetection.config

import play.api.Configuration
import play.api.libs.json.{Format, Json}

import javax.inject.{Inject, Provider, Singleton}

@Singleton
class AppConfigProvider @Inject()(
  configuration: Configuration
) extends Provider[AppConfig] {

  private def toRule(configuration: Configuration): Rule =
    Rule(
      id                = configuration.get[String ]("id"),
      scope             = configuration.get[String ]("scope"),
      regex             = configuration.get[String ]("regex"),
      description       = configuration.get[String ]("description"),
      ignoredFiles      = configuration.getOptional[Seq[String]]("ignoredFiles"     ).getOrElse(Seq.empty).toList,
      ignoredExtensions = configuration.getOptional[Seq[String]]("ignoredExtensions").getOrElse(Seq.empty).toList,
      ignoredContent    = configuration.getOptional[Seq[String]]("ignoredContent"   ).getOrElse(Seq.empty).toList,
      priority          = configuration.getOptional[String     ]("priority"         ).getOrElse(Rule.Priority.Low),
      draft             = configuration.getOptional[Boolean    ]("draft"            ).getOrElse(false)
    )

  private lazy val appConfig =
    AppConfig(
      allRules                  = AllRules(
                                    publicRules  = configuration.get[Seq[Configuration]]("allRules.publicRules" ).map(toRule).toList,
                                    privateRules = configuration.get[Seq[Configuration]]("allRules.privateRules").map(toRule).toList
                                  )
    , githubSecrets             = GithubSecrets(personalAccessToken = configuration.get[String]("githubSecrets.personalAccessToken"))
    , maxLineLength             = configuration.get[Int]("maxLineLength")
    , clearingCollectionEnabled = configuration.get[Boolean]("clearingCollectionEnabled")
    , warningMessages           = { val msgs = configuration.get[Configuration]("warningMessages")
                                    msgs.keys.map { k => k -> msgs.get[String](k) }.toMap
                                  }
    , alerts                    = Alerts(SlackConfig(
                                    enabled                = configuration.get[Boolean    ]("alerts.slack.enabled"),
                                    adminChannel           = configuration.get[String     ]("alerts.slack.adminChannel"),
                                    defaultAlertChannel    = configuration.get[String     ]("alerts.slack.defaultAlertChannel"),
                                    username               = configuration.get[String     ]("alerts.slack.username"),
                                    iconEmoji              = configuration.get[String     ]("alerts.slack.iconEmoji"),
                                    sendToAlertChannel     = configuration.get[Boolean    ]("alerts.slack.sendToAlertChannel"),
                                    sendToTeamChannels     = configuration.get[Boolean    ]("alerts.slack.sendToTeamChannels"),
                                    messageText            = configuration.get[String     ]("alerts.slack.messageText"),
                                    lastAttemptMessageText = configuration.get[String     ]("alerts.slack.lastAttemptMessageText"),
                                    leakDetectionUri       = configuration.get[String     ]("alerts.slack.leakDetectionUri"),
                                    warningText            = configuration.get[String     ]("alerts.slack.warningText"),
                                    warningsToAlert        = configuration.get[Seq[String]]("alerts.slack.warningsToAlert")
                                  ))
    , maxRetries                = configuration.get[Int]("maxRetries")
    )

  override def get(): AppConfig =
    appConfig
}

final case class AppConfig(
  allRules                 : AllRules,
  githubSecrets            : GithubSecrets,
  maxLineLength            : Int,
  clearingCollectionEnabled: Boolean,
  warningMessages          : Map[String, String],
  alerts                   : Alerts,
  maxRetries               : Int
)

final case class AllRules(
  publicRules : List[Rule],
  privateRules: List[Rule]
)

object AllRules {
  implicit val format = Json.format[AllRules]
}

final case class Rule(
  id:                String,
  scope:             String,
  regex:             String,
  description:       String,
  ignoredFiles:      List[String] = Nil,
  ignoredExtensions: List[String] = Nil,
  ignoredContent:    List[String] = Nil,
  priority:          String       = Rule.Priority.Low,
  draft:             Boolean      = false
)

object Rule {
  implicit val format: Format[Rule] = Json.format[Rule]

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

final case class GithubSecrets(
  personalAccessToken: String,
)

final case class RuleExemption(
  ruleId   : String,
  filePaths: Seq[String],
  text     : Option[String] = None
)

object RuleExemption {
  implicit val format = Json.format[RuleExemption]
}


final case class Alerts(
  slack: SlackConfig
)

final case class SlackConfig(
  enabled               : Boolean,
  adminChannel          : String,
  defaultAlertChannel   : String,
  username              : String,
  iconEmoji             : String,
  sendToAlertChannel    : Boolean,
  sendToTeamChannels    : Boolean,
  messageText           : String,
  lastAttemptMessageText: String,
  leakDetectionUri      : String,
  warningText           : String,
  warningsToAlert       : Seq[String]
)
