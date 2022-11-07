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

package uk.gov.hmrc.leakdetection.services

import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}

import javax.inject.{Inject, Singleton}

@Singleton
class RuleService @Inject()(configLoader: ConfigLoader) {

  lazy private val privateRules = configLoader.cfg.allRules.privateRules
  lazy private val publicRules = configLoader.cfg.allRules.publicRules

  def getAllRules(): Seq[Rule] =
    (privateRules ::: publicRules).distinct
}
