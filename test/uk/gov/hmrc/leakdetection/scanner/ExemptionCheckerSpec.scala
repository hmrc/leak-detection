package uk.gov.hmrc.leakdetection.scanner

import ammonite.ops.{tmp, write}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Priority
import uk.gov.hmrc.leakdetection.model.UnusedExemption

import java.io.File

class ExemptionCheckerSpec extends AnyWordSpec with Matchers {
  val exemptionChecker = new ExemptionChecker

  "exemption checker" should {
    "return empty list if no exemptions defined" in {
      val dir     = givenExemptions("")
      val results = exemptionChecker.run(Seq.empty, dir)

      results shouldBe empty
    }
    "return empty list if all exemptions cover an excluded match" in {
      val dir = givenExemptions(
        """
          |leakDetectionExemptions:
          |  - ruleId: 'rule-1'
          |    filePath: /dir/file1
          |    text: 'some text'
          |  - ruleId: 'rule-2'
          |    filePaths:
          |       - /dir/file2
          |       - /dir/file3
        """.stripMargin
      )

      val matchedResults = Seq(
        aMatchedResult.copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "with some text that matches", isExcluded = true),
        aMatchedResult.copy(ruleId = "rule-2", filePath = "/dir/file2", isExcluded = true),
        aMatchedResult.copy(ruleId = "rule-2", filePath = "/dir/file3", isExcluded = true)
      )

      val results = exemptionChecker.run(matchedResults, dir)

      results shouldBe empty
    }
    "return all exemptions as unused when no excluded results" in {
      val dir = givenExemptions(
        """
          |leakDetectionExemptions:
          |  - ruleId: 'rule-1'
          |    filePath: /dir/file1
          |    text: 'some text'
          |  - ruleId: 'rule-2'
          |    filePaths:
          |       - /dir/file2
          |       - /dir/file3
        """.stripMargin
      )

      val matchedResults = Seq(
        aMatchedResult.copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "some other text", isExcluded = false),
        aMatchedResult.copy(ruleId = "rule-3", filePath = "/dir/file1", isExcluded = false)
      )

      val results = exemptionChecker.run(matchedResults, dir)

      results shouldBe Seq(
        UnusedExemption("rule-1", "/dir/file1", Some("some text")),
        UnusedExemption("rule-2", "/dir/file2", None),
        UnusedExemption("rule-2", "/dir/file3", None),
      )
    }
    "correctly distinguish between exemptions for same rule and file with different text" in {
      val dir = givenExemptions(
        """
          |leakDetectionExemptions:
          |  - ruleId: 'rule-1'
          |    filePath: /dir/file1
          |    text: 'some text'
          |  - ruleId: 'rule-1'
          |    filePath: /dir/file1
          |    text: 'other text'
        """.stripMargin
      )

      val matchedResults = Seq(
        aMatchedResult
          .copy(ruleId = "rule-1", filePath = "/dir/file1", lineText = "with some text that matches", isExcluded = true)
      )

      val results = exemptionChecker.run(matchedResults, dir)

      results shouldBe Seq(UnusedExemption("rule-1", "/dir/file1", Some("other text")))
    }
  }

  def givenExemptions(content: String): File = {
    val wd = tmp.dir()
    write(wd / 'zip_file_name_xyz / "repository.yaml", content)
    wd.toNIO.toFile
  }

  val aMatchedResult = MatchedResult("", "", "", 0, "", "", List(), Priority.Low)
}
