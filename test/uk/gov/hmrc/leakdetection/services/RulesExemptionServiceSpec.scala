/*
 * Copyright 2018 HM Revenue & Customs
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

import java.io.File

import ammonite.ops.{Path, tmp, write}
import org.scalatest.{Matchers, WordSpec}
import uk.gov.hmrc.leakdetection.config.RuleExemption

import scala.util.Random

class RulesExemptionServiceSpec extends WordSpec with Matchers {

  "Rules exemption service" should {
    "extract rule exemptions from a configuration file" in new Setup {

      val configContent =
        """
          |leak-detection-exemptions: 
          |  - rule-id: '1'
          |    file-name: foo.scala
          |  - rule-id: 'id2'
          |    file-name: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List(RuleExemption("1", "foo.scala"), RuleExemption("id2", "bar.py"))

      val parsedRules = rulesExemptionService.parseConfig(dir.toIO)

      parsedRules shouldBe expectedRules

    }

    "return empty list if no configuration file exists" in new Setup {
      val nonexistentFile = new File(Random.nextString(10))
      val parsedRules     = rulesExemptionService.parseConfig(nonexistentFile)

      parsedRules shouldBe Nil
    }

    "return empty list if config exists but exemptions are not defined" in new Setup {
      val emptyContent = ""
      createFileForTest(emptyContent)

      val parsedRules = rulesExemptionService.parseConfig(dir.toIO)

      parsedRules shouldBe Nil
    }
    "return empty list if config exists but has syntax errors" in new Setup {
      val emptyContent =
        """
          |foo
          |- bar: 1
        """.stripMargin
      createFileForTest(emptyContent)

      val parsedRules = rulesExemptionService.parseConfig(dir.toIO)

      parsedRules shouldBe Nil
    }

    "skip entries if incorrectly defined, e.g. misspelled" in new Setup {
      val configContent =
        """
          |leak-detection-exemptions: 
          |  - ruleId: '1' # camel case instead of snake case
          |    file-name: foo.scala
          |  - rule-id: 'id2'
          |    file-name: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List(RuleExemption("id2", "bar.py"))

      val parsedRules = rulesExemptionService.parseConfig(dir.toIO)

      parsedRules shouldBe expectedRules

    }
  }

  trait Setup {
    val dir: Path = tmp.dir()

    def createFileForTest(content: String) = {
      write(dir / "repository.yaml", content)
      dir
    }

    val rulesExemptionService = new RulesExemptionService
  }

}
