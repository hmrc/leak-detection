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

import javax.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.Action
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.scanner.RegexScanner
import uk.gov.hmrc.play.bootstrap.controller.BaseController

class RulesController @Inject()(configLoader: ConfigLoader) extends BaseController {

  import configLoader.cfg.allRules._

  def testPublicRules() = testRules(publicRules)

  def testPrivateRules() = testRules(privateRules)

  private def testRules(rules: List[Rule]) =
    Action(parse.tolerantText) { implicit request =>
      val scanners = rules.map(new RegexScanner(_))
      val matches  = scanners.flatMap(_.scan(request.body))

      Ok(Json.toJson(matches))
    }

}
