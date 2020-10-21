/*
 * Copyright 2020 HM Revenue & Customs
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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.RuleExemption

import scala.util.Random

class RulesExemptionParserSpec extends AnyWordSpec with Matchers {

  "Rules exemption service" should {
    "extract rule exemptions from a configuration file" in new Setup {

      val configContent =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePath: foo.scala
          |  - ruleId: 'id2'
          |    filePath: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List(RuleExemption("1", Seq("foo.scala")), RuleExemption("id2", Seq("bar.py")))

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe expectedRules

    }

    "Support multiple paths with the same id" in new Setup {

      val configContent =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePath: foo.scala
          |  - ruleId: '1'
          |    filePath: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List(RuleExemption("1", Seq("foo.scala")), RuleExemption("1", Seq("bar.py")))

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe expectedRules

    }

    "Support rule exemption with multiple paths" in new Setup {

      val configContent =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePaths: 
          |      - foo.scala
          |      - bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List(RuleExemption("1", Seq("foo.scala", "bar.py")))

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe expectedRules

    }

    "Ignore leakDetectionExemptions with bad syntax" in new Setup {

      val configContent =
        """
          |leakDetectionExemptions: boom
        """.stripMargin

      createFileForTest(configContent)

      val parsedRules: List[RuleExemption] = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe empty

    }
    "Ignore leakDetectionExemptions with bad syntax in a rule" in new Setup {

      val configContent =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePath:
          |      - boom
          |  - ruleId: '2'
          |    filePaths:
          |      - foo.scala
          |      - bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List()

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe expectedRules

    }

    "return empty list if no configuration file exists" in new Setup {
      val nonexistentFile = new File(Random.nextString(10))
      val parsedRules     = RulesExemptionParser.parseServiceSpecificExemptions(nonexistentFile)

      parsedRules shouldBe Nil
    }

    "return empty list if config exists but exemptions are not defined" in new Setup {
      val emptyContent = ""
      createFileForTest(emptyContent)

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe Nil
    }
    "return empty list if config exists but has syntax errors" in new Setup {
      val emptyContent =
        """
          |foo
          |- bar: 1
        """.stripMargin
      createFileForTest(emptyContent)

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe Nil
    }

    "skip entries if incorrectly defined, e.g. misspelled" in new Setup {
      val configContent =
        """
          |leakDetectionExemptions:
          |  - rule-id: '1' # snake case instead of camelCase
          |    filePath: foo.scala
          |  - ruleId: 'id2'
          |    filePath: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules = List(RuleExemption("id2", Seq("bar.py")))

      val parsedRules = RulesExemptionParser.parseServiceSpecificExemptions(dir.toIO)

      parsedRules shouldBe expectedRules

    }
  }

  trait Setup {
    val dir: Path = tmp.dir()

    def createFileForTest(content: String) = {
      write(dir / "repository.yaml", content)
      dir
    }

  }

}
