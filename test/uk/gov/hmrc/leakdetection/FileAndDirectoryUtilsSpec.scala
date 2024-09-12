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

package uk.gov.hmrc.leakdetection

import os.{Path, makeDir, temp, write}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FileAndDirectoryUtilsSpec extends AnyWordSpec with Matchers:
  "getPathRelativeToProjectRoot" should:
    "remove temp dir and randomized repo name leaving just a file path relative to repo root" in:
      val dir: Path          = temp.dir()
      val randomizedRepoName = "foo_abc"
      val aSubdirectory      = "some_subdir"
      val fileName           = "my-file.txt"
      val file               = dir / randomizedRepoName / aSubdirectory / fileName

      write(file, "some-file-content", createFolders = true)

      val relativePath = FileAndDirectoryUtils.getFilePathRelativeToProjectRoot(dir.toIO, file.toIO)

      relativePath shouldBe s"/$aSubdirectory/$fileName"

  "getSubdirName" should:
    "return a name of a single directory in a given directory" in:
      val dir    = temp.dir()
      val subdir = dir / "subdir"

      makeDir(subdir)

      FileAndDirectoryUtils.getSubdirName(dir.toIO) shouldBe subdir.toIO
