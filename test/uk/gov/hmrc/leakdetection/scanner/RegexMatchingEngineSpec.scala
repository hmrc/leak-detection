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
          Rule(".*secretA.*", "tag 1"),
          Rule(".*secretB.*", "tag 2"),
          Rule(".*secretC.*", "tag 3")
        )
      )

      matches should have size 6

      matches should contain(
        Result("/dir1/fileA", MatchedResult("matching on: secretA", 1, "tag 1")))
      matches should contain(
        Result("/dir1/fileA", MatchedResult("matching on: secretA again", 2, "tag 1")))

      matches should contain(
        Result("/dir2/fileB", MatchedResult("matching on: secretB", 2, "tag 2")))
      matches should contain(
        Result("/dir2/fileB", MatchedResult("matching on: secretB again", 3, "tag 2")))

      matches should contain(
        Result("/dir2/dir3/fileC", MatchedResult("matching on: secretC", 1, "tag 3")))
      matches should contain(
        Result("/dir2/dir3/fileC", MatchedResult("matching on: secretC again", 2, "tag 3")))
    }

  }

}
