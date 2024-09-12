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

package uk.gov.hmrc.leakdetection.scanner

import os.{temp, write}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption}
import uk.gov.hmrc.leakdetection.config.Rule.Priority

class RegexMatchingEngineSpec extends AnyWordSpec with MockitoSugar with Matchers:

  "run" should:
    "scan all the files in all subdirectories and return a report with correct file paths" in:
      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir1") / "fileA", "matching on: secretA\nmatching on: secretA again", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir2") / "fileB", "\nmatching on: secretB\nmatching on: secretB again", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir2") / "dir3" / "fileC", "matching on: secretC\nmatching on: secretC again", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir2") / "dir3" / "fileD", "no match\nto be found in this file\n", createFolders = true)

      val rules = List(
        Rule("rule-1", Rule.Scope.FILE_CONTENT, "secretA", "descr 1", priority = Rule.Priority.High),
        Rule("rule-2", Rule.Scope.FILE_CONTENT, "secretB", "descr 2"),
        Rule("rule-3", Rule.Scope.FILE_CONTENT, "secretC", "descr 3"),
        Rule("rule-4", Rule.Scope.FILE_NAME, "fileC", "file with secrets")
      )

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(
          explodedZipDir = wd.toNIO.toFile,
          List.empty
        )

      results should have size 7

      results should contain(
        MatchedResult(
            filePath    = "/dir1/fileA",
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "matching on: secretA",
            lineNumber  = 1,
            ruleId      = "rule-1",
            description = "descr 1",
            matches     = List(Match(start = 13, end = 20)),
            priority    = Rule.Priority.High
        )
      )
      results should contain(
        MatchedResult(
            filePath    = "/dir1/fileA",
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "matching on: secretA again",
            lineNumber  = 2,
            ruleId      = "rule-1",
            description = "descr 1",
            matches     = List(Match(start = 13, end = 20)),
            priority    = Rule.Priority.High
          )
      )

      results should contain(
          MatchedResult(
            filePath    = "/dir2/fileB",
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "matching on: secretB",
            lineNumber  = 2,
            ruleId      = "rule-2",
            description = "descr 2",
            matches     = List(Match(start = 13, end = 20)),
            priority    = Rule.Priority.Low
          )
      )
      results should contain(
        MatchedResult(
          filePath    = "/dir2/fileB",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "matching on: secretB again",
          lineNumber  = 3,
          ruleId      = "rule-2",
          description = "descr 2",
          matches     = List(Match(start = 13, end = 20)),
          priority    = Rule.Priority.Low
        )
      )

      results should contain(
        MatchedResult(
          filePath    = "/dir2/dir3/fileC",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "matching on: secretC",
          lineNumber  = 1,
          ruleId      = "rule-3",
          description = "descr 3",
          matches     = List(Match(start = 13, end = 20)),
          priority    = Rule.Priority.Low
        )
      )
      results should contain(
        MatchedResult(
            filePath    = "/dir2/dir3/fileC",
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "matching on: secretC again",
            lineNumber  = 2,
            ruleId      = "rule-3",
            description = "descr 3",
            matches     = List(Match(start = 13, end = 20)),
            priority    = Rule.Priority.Low
          )
      )
      results should contain(
        MatchedResult(
          filePath    = "/dir2/dir3/fileC",
          scope       = Rule.Scope.FILE_NAME,
          lineText    = "fileC",
          lineNumber  = 1,
          ruleId      = "rule-4",
          description = "file with secrets",
          matches     = List(Match(start = 0, end = 5)),
          priority    = Rule.Priority.Low
        )
      )

    "filter out results that match rules ignoredFiles" in:
      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir1") / "fileA", "matching on: secretA\nmatching on: secretA again", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir2") / "fileB", "\nmatching on: secretB\nmatching on: secretB again", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir2") / "dir3" / "fileC", "matching on: secretC\nmatching on: secretC again", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir2") / "dir3" / "fileD", "no match\nto be found in this file\n", createFolders = true)

      val rules = List(
        Rule("rule-1", Rule.Scope.FILE_CONTENT, "secretA", "descr 1", List("/dir1/fileA")),
        Rule("rule-2", Rule.Scope.FILE_CONTENT, "secretB", "descr 2"),
        Rule("rule-3", Rule.Scope.FILE_NAME, "fileA", "file with some secrets"),
        Rule("rule-4", Rule.Scope.FILE_NAME, "fileB", "file with more secrets", List("/dir2/fileB"))
      )

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, List.empty)

      results should have size 3

      results should contain(
        MatchedResult(
            filePath = "/dir2/fileB",
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "matching on: secretB",
            lineNumber  = 2,
            ruleId      = "rule-2",
            description = "descr 2",
            matches     = List(Match(start = 13, end = 20)),
            priority    = Rule.Priority.Low
          )
      )
      results should contain(
        MatchedResult(
            filePath    = "/dir2/fileB",
            scope       = Rule.Scope.FILE_CONTENT,
            lineText    = "matching on: secretB again",
            lineNumber  = 3,
            ruleId      = "rule-2",
            description = "descr 2",
            matches     = List(Match(start = 13, end = 20)),
            priority    = Rule.Priority.Low
          )
      )

      results should contain(
        MatchedResult(
            filePath    = "/dir1/fileA",
            scope       = Rule.Scope.FILE_NAME,
            lineText    = "fileA",
            lineNumber  = 1,
            ruleId      = "rule-3",
            description = "file with some secrets",
            matches     = List(Match(start = 0, end = 5)),
            priority    = Rule.Priority.Low
          )
      )

    "flag as excluded if all results match with at least one rules ignoredContent" in:
      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / "fileA", "matching on: AA000000A", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / "fileB", "matching on: AA111111A", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / "fileC", "matching on: AA000000A and AA111111A", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / "fileD", "matching on: AA000000A and AA000111A", createFolders = true)

      val rules: List[Rule] =
        List(Rule("rule-1", Rule.Scope.FILE_CONTENT, "AA[0-9]{6}A", "descr 1", ignoredContent = List("000", "222")))

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, List.empty)

      val aMatchedResult: MatchedResult =
        MatchedResult("", Rule.Scope.FILE_CONTENT, "", 1, "rule-1", "descr 1", List(Match(start = 13, end = 22)), Priority.Low)
      
      results should contain theSameElementsAs Seq(
        aMatchedResult.copy(filePath="/fileA", lineText = "matching on: AA000000A", isExcluded = true),
        aMatchedResult.copy(filePath="/fileB", lineText = "matching on: AA111111A"),
        aMatchedResult.copy(filePath="/fileC", lineText = "matching on: AA000000A and AA111111A", matches = List(Match(start = 13, end = 22), Match(start = 27, end = 36))),
        aMatchedResult.copy(filePath="/fileD", lineText = "matching on: AA000000A and AA000111A", matches = List(Match(start = 13, end = 22), Match(start = 27, end = 36)), isExcluded = true),
      )

    "flag as excluded if filename matches file level exemption in repository.yaml" in:
      val exemptions: List[RuleExemption] =
        List(
          RuleExemption("rule-1", Seq("/dir/file1", "/dir/file2"), None),
          RuleExemption("rule-2", Seq("/dir/file4.key"), None)
        )

      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file1", "secret=false-positive\neven if multiple matches for secret=real-secret", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file2", "secret=other-value", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file3", "secret=anything", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file4.key", "", createFolders = true)

      val rules: List[Rule] =
        List(
          Rule("rule-1", Rule.Scope.FILE_CONTENT, "secret=", "leaked secret found for rule 1"),
          Rule("rule-2", Rule.Scope.FILE_NAME, """^.*\.key$""", "leaked secret found in filename")
        )

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, exemptions)

      results should contain theSameElementsAs Seq(
        MatchedResult(
          filePath    = "/dir/file1",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "secret=false-positive",
          lineNumber  = 1,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 0, end = 7)),
          priority    = Rule.Priority.Low,
          isExcluded = true
        ),
        MatchedResult(
          filePath    = "/dir/file1",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "even if multiple matches for secret=real-secret",
          lineNumber  = 2,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 29, end = 36)),
          priority    = Rule.Priority.Low,
          isExcluded = true
        ),
        MatchedResult(
          filePath    = "/dir/file2",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "secret=other-value",
          lineNumber  = 1,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 0, end = 7)),
          priority    = Rule.Priority.Low,
          isExcluded = true
        ),
        MatchedResult(
          filePath    = "/dir/file3",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "secret=anything",
          lineNumber  = 1,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 0, end = 7)),
          priority    = Rule.Priority.Low
        ),
        MatchedResult(
          filePath    = "/dir/file4.key",
          scope       = Rule.Scope.FILE_NAME,
          lineText    = "file4.key",
          lineNumber  = 1,
          ruleId      = "rule-2",
          description = "leaked secret found in filename",
          matches     = List(Match(0, 9)),
          priority    = Rule.Priority.Low,
          isExcluded    = true
        )
      )
      
    "flag as excluded if line text matches supplied exemption text in repository.yaml" in:
      val exemptions: List[RuleExemption] =
        List(
          RuleExemption("rule-1", Seq("/dir/file1"), Some("false-positive")),
          RuleExemption("rule-2", Seq("/dir/file1"), Some("some-other-rule-false-positive")),
          RuleExemption("rule-1", Seq("/dir/file2"), Some("some-other-false-positive"))
        )

      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file1", "no match to be found on: secret=false-positive\nmatch should be found on secret=real-secret\nrule 2 match should still be found on: key=false-positive", createFolders = true)
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file2", "match should be found on: secret=false-positive in this file", createFolders = true)

      val rules: List[Rule] =
        List(
          Rule("rule-1", Rule.Scope.FILE_CONTENT, "secret=", "leaked secret found for rule 1"),
          Rule("rule-2", Rule.Scope.FILE_CONTENT, "key=", "leaked secret found for rule 2")
        )

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, exemptions)

      results should contain theSameElementsAs Seq(
        MatchedResult(
          filePath    = "/dir/file1",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "no match to be found on: secret=false-positive",
          lineNumber  = 1,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 25, end = 32)),
          priority    = Rule.Priority.Low,
          isExcluded = true
        ),
        MatchedResult(
          filePath    = "/dir/file1",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "match should be found on secret=real-secret",
          lineNumber  = 2,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 25, end = 32)),
          priority    = Rule.Priority.Low
        ),
        MatchedResult(
          filePath    = "/dir/file1",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "rule 2 match should still be found on: key=false-positive",
          lineNumber  = 3,
          ruleId      = "rule-2",
          description = "leaked secret found for rule 2",
          matches     = List(Match(start = 39, end = 43)),
          priority    = Rule.Priority.Low
        ),
        MatchedResult(
          filePath    = "/dir/file2",
          scope       = Rule.Scope.FILE_CONTENT,
          lineText    = "match should be found on: secret=false-positive in this file",
          lineNumber  = 1,
          ruleId      = "rule-1",
          description = "leaked secret found for rule 1",
          matches     = List(Match(start = 26, end = 33)),
          priority    = Rule.Priority.Low
        )
      )

    "flag in-line exceptions as excluded" in:
      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file",
        "first match on: secret\n" +
          "//LDS ignore\n" +
          "ignore match on: secret\n" +
          "second match on: secret\n" +
          "# hey, LDS ignore this with good reason\n" +
          "ignore another match on: secret\n",
        createFolders = true
      )

      val rules: List[Rule] =
        List(Rule("rule", Rule.Scope.FILE_CONTENT, "secret", "leaked secret"))

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, List.empty)

      val aMatchedResult: MatchedResult =
        MatchedResult("/dir/file", "fileContent", "", 0, "rule", "leaked secret", List(), Priority.Low)
      
      results shouldBe Seq(
        aMatchedResult.copy(lineText = "first match on: secret", lineNumber = 1, matches = List(Match(16, 22))),
        aMatchedResult.copy(lineText = "ignore match on: secret", lineNumber = 3, matches = List(Match(17, 23)), isExcluded = true),
        aMatchedResult.copy(lineText = "second match on: secret", lineNumber = 4, matches = List(Match(17, 23))),
        aMatchedResult.copy(lineText = "ignore another match on: secret", lineNumber = 6, matches = List(Match(25, 31)), isExcluded = true),
      )

    "handle fileContent rules that may span multiple lines" in:
      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file",
        "sso.encryption.key:\n" +
          "ENC[GPG,blah]",
        createFolders = true
      )

      val rules: List[Rule] =
        List(
          Rule("sso_encryption_key", Rule.Scope.FILE_CONTENT, """sso\.encryption\.key\s*(=|:|->)\s*(?!(\s*ENC\[))""", "Unencrypted sso.encryption.key")
        )

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, List.empty)

      results shouldBe Nil

    "not filter out genuine hits" in:
      val wd = temp.dir()
      write(wd / Symbol("zip_file_name_xyz") / Symbol("dir") / "file",
        "sso.encryption.key:\n" +
          "myplaintextkey",
        createFolders = true
      )

      val rules: List[Rule] =
        List(
          Rule("sso_encryption_key", Rule.Scope.FILE_CONTENT, """sso\.encryption\.key\s*(=|:|->)\s*(?!(\s*ENC\[))""", "Unencrypted sso.encryption.key")
        )

      val results: List[MatchedResult] =
        RegexMatchingEngine(rules, Int.MaxValue).run(explodedZipDir = wd.toNIO.toFile, List.empty)

      results shouldBe Seq(
        MatchedResult("/dir/file", "fileContent", "sso.encryption.key:", 1, "sso_encryption_key", "Unencrypted sso.encryption.key", List(Match(0, 19)), "low", false, false, None)
      )
