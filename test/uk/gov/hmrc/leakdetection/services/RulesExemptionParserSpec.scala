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

package uk.gov.hmrc.leakdetection.services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.RuleExemption
import uk.gov.hmrc.leakdetection.model.WarningMessageType.*
import uk.gov.hmrc.leakdetection.model.WarningMessageType
import uk.gov.hmrc.leakdetection.utils.TestFileUtils._

import java.io.File
import java.nio.file.Path
import scala.util.Random

class RulesExemptionParserSpec extends AnyWordSpec with Matchers:

  "Rules exemption service" should:
    "extract rule exemptions from a configuration file" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePath: foo.scala
          |  - ruleId: 'id2'
          |    filePath: bar.py
        """.stripMargin

      createFileForTest(configContent)

      val expected: Either[WarningMessageType, List[RuleExemption]] =
        Right(
          List(
            RuleExemption("1", Seq("foo.scala")),
            RuleExemption("id2", Seq("bar.py"))
          )
        )

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expected

    "Support multiple paths with the same id" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePath: foo.scala
          |  - ruleId: '1'
          |    filePath: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expected: Either[WarningMessageType, List[RuleExemption]] =
        Right(
          List(
            RuleExemption("1", Seq("foo.scala")),
            RuleExemption("1", Seq("bar.py"))
          )
        )

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expected

    "Support rule exemption with multiple paths" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePaths:
          |      - foo.scala
          |      - bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules: Either[WarningMessageType, List[RuleExemption]] =
        Right(List(RuleExemption("1", Seq("foo.scala", "bar.py"))))

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expectedRules

    "Support rule exemption with text" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1'
          |    filePaths:
          |      - foo.scala
          |    text: 'false-positive'
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules: Either[WarningMessageType, List[RuleExemption]] =
        Right(List(RuleExemption("1", Seq("foo.scala"), Some("false-positive"))))

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] = RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expectedRules

    "return MissingRuleId when leakDetectionExemptions has missing ruleId field" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - filePath: /dir/file1
          |    text: "false-positive"
        """.stripMargin

      createFileForTest(configContent)

      val expected: Either[WarningMessageType, List[RuleExemption]] =
        Left(ParseFailure)

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expected

    "Ignore leakDetectionExemptions with bad syntax in a rule" in new Setup:
      val configContent: String =
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
      val expectedRules: Either[WarningMessageType, List[RuleExemption]] =
        Left(ParseFailure)

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expectedRules

    "return MissingRepositoryYamlFile Warning if no configuration file exists" in new Setup:
      val nonexistentFile: File =
        File(Random.nextString(10))
      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(nonexistentFile)

      parsedRules shouldBe Left(MissingRepositoryYamlFile)

    "return empty list if config exists but exemptions are not defined" in new Setup:
      val emptyContent = ""
      createFileForTest(emptyContent)

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe Right(List.empty)

    "return ParseFailure if key `leakDetectionExemptions` exists but has syntax errors" in new Setup:
      val brokenYaml: String =
        """
          |foo
          |leakDetectionExemptions
          |- bar: 1
        """.stripMargin
      createFileForTest(brokenYaml)

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe Left(ParseFailure)

    "return ParseFailure if config has wrong type" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - ruleId: '1' # snake case instead of camelCase
          |    filePath: foo.scala
          |    text:
          |      - bad-array
          |  - ruleId: 'id2'
          |    filePath: bar.py
          |    text: ok
        """.stripMargin

      createFileForTest(configContent)

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe Left(ParseFailure)

    "handle misconfigured exemptions and still parse valid exemptions" in new Setup:
      val configContent: String =
        """
          |leakDetectionExemptions:
          |  - rule-id: '1' # snake case instead of camelCase
          |    filePath: foo.scala
          |  - ruleId: 'id2'
          |    filePath: bar.py
        """.stripMargin

      createFileForTest(configContent)
      val expectedRules: Either[WarningMessageType, List[RuleExemption]] =
        Left(ParseFailure)

      val parsedRules: Either[WarningMessageType, List[RuleExemption]] =
        RulesExemptionParser.parseServiceSpecificExemptions(dir.toFile)

      parsedRules shouldBe expectedRules

  trait Setup:
    val dir: Path = tempDir()

    def createFileForTest(content: String): Path =
      write(dir.resolve("repository.yaml"), content)
      dir
