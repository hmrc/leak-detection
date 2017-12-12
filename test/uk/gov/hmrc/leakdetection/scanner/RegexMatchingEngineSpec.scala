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

package uk.gov.hmrc.leakdetection.scanner

import ammonite.ops.{Path, rm, tmp, write}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import uk.gov.hmrc.leakdetection.config.Rule

class RegexMatchingEngineSpec
    extends FreeSpec
    with MockitoSugar
    with Matchers
    with BeforeAndAfterAll {

  val wd: Path = tmp.dir()

  def createFilesForTest() = {
    write(
      wd / 'zip_file_name_xyz / 'dir1 / "fileA",
      "matching on: secretA\nmatching on: secretA again")
    write(
      wd / 'zip_file_name_xyz / 'dir2 / "fileB",
      "\nmatching on: secretB\nmatching on: secretB again")
    write(
      wd / 'zip_file_name_xyz / 'dir2 / "dir3" / "fileC",
      "matching on: secretC\nmatching on: secretC again")
    write(
      wd / 'zip_file_name_xyz / 'dir2 / "dir3" / "fileD",
      "no match\nto be found in this file\n")
    wd
  }

  override protected def afterAll(): Unit =
    rm ! wd

  "run" - {
    "should scan all the files in all subdirectories and return a report with correct file paths" in {
      val rootDir = createFilesForTest()

      val matches = new RegexMatchingEngine().run(
        explodedZipDir = rootDir.toNIO.toFile,
        rules = Seq(
          Rule("rule-1", "secretA", "descr 1"),
          Rule("rule-2", "secretB", "descr 2"),
          Rule("rule-3", "secretC", "descr 3")
        )
      )

      matches should have size 6

      matches should contain(
        Result(
          filePath = "/dir1/fileA",
          scanResults = MatchedResult(
            lineText    = "matching on: secretA",
            lineNumber  = 1,
            ruleId      = "rule-1",
            description = "descr 1",
            matches     = List(Match(start = 13, end = 20, value = "secretA")))
        )
      )
      matches should contain(
        Result(
          filePath = "/dir1/fileA",
          scanResults = MatchedResult(
            lineText    = "matching on: secretA again",
            lineNumber  = 2,
            ruleId      = "rule-1",
            description = "descr 1",
            matches     = List(Match(start = 13, end = 20, value = "secretA")))
        )
      )

      matches should contain(
        Result(
          filePath = "/dir2/fileB",
          scanResults = MatchedResult(
            lineText    = "matching on: secretB",
            lineNumber  = 2,
            ruleId      = "rule-2",
            description = "descr 2",
            matches     = List(Match(start = 13, end = 20, value = "secretB")))
        )
      )
      matches should contain(
        Result(
          filePath = "/dir2/fileB",
          scanResults = MatchedResult(
            lineText    = "matching on: secretB again",
            lineNumber  = 3,
            ruleId      = "rule-2",
            description = "descr 2",
            matches     = List(Match(start = 13, end = 20, value = "secretB")))
        )
      )

      matches should contain(
        Result(
          filePath = "/dir2/dir3/fileC",
          scanResults = MatchedResult(
            lineText    = "matching on: secretC",
            lineNumber  = 1,
            ruleId      = "rule-3",
            description = "descr 3",
            matches     = List(Match(start = 13, end = 20, value = "secretC")))
        )
      )
      matches should contain(
        Result(
          filePath = "/dir2/dir3/fileC",
          scanResults = MatchedResult(
            lineText    = "matching on: secretC again",
            lineNumber  = 2,
            ruleId      = "rule-3",
            description = "descr 3",
            matches     = List(Match(start = 13, end = 20, value = "secretC")))
        )
      )
    }

  }

}
