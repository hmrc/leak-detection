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

import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.model.WarningMessageType.*
import uk.gov.hmrc.leakdetection.utils.TestFileUtils._

import scala.util.Random

class RepoVisibilityCheckerSpec extends AnyWordSpec with Matchers:

  val checker: RepoVisibilityChecker =
    RepoVisibilityChecker()
    
  "repositoryYamlChecker" should:

    "return a warning if the repository.yaml file is not found" in:
      val dir: Path = Files.createTempDirectory(null)
      makeDir(dir.resolve("test"))

      checker.checkVisibilityDefinedCorrectly(dir.toFile, Random.nextBoolean()) shouldBe Some(MissingRepositoryYamlFile)

    "confirm if repoVisibility has correct value for a public repository" in:
      val yamlFileContents = "repoVisibility: public_0C3F0CE3E6E6448FAD341E7BFA50FCD333E06A20CFF05FCACE61154DDBBADF71"
      val dir: Path = tempDir()
      val subDir: Path = dir.resolve("test")
      makeDir(subDir)
      write(subDir.resolve("repository.yaml"), yamlFileContents)

      checker.checkVisibilityDefinedCorrectly(dir.toFile, isPrivate = false) shouldBe None

    "confirm if repoVisibility has correct value for a private repository" in:
      val yamlFileContents = "repoVisibility: private_12E5349CFB8BBA30AF464C24760B70343C0EAE9E9BD99156345DD0852C2E0F6F"
      val dir: Path = tempDir()
      val subDir: Path = dir.resolve("test")
      makeDir(subDir)
      write(subDir.resolve("repository.yaml"), yamlFileContents)

      checker.checkVisibilityDefinedCorrectly(dir.toFile, isPrivate = true) shouldBe None

    "return a warning if the repoVisibility value is incorrect" in:
      val yamlFileContents = "repoVisibility: private_12E5349CFB8BBA30AF464C24760B70343C0EAE9E9BD99156345DD0852C2E0F6F"
      val dir: Path = tempDir()
      val subDir: Path = dir.resolve("test")
      makeDir(subDir)
      write(subDir.resolve("repository.yaml"), yamlFileContents)

      checker.checkVisibilityDefinedCorrectly(dir.toFile, false) shouldBe Some(InvalidEntry)

    "return a warning if the file doesn't have the repoVisibility key" in:
      val yamlFileContents = "foo: bar"
      val dir: Path = tempDir()
      val subDir: Path = dir.resolve("test")
      makeDir(subDir)
      write(subDir.resolve("repository.yaml"), yamlFileContents)

      checker.checkVisibilityDefinedCorrectly(dir.toFile, isPrivate = Random.nextBoolean()) shouldBe Some(MissingEntry)

    "return a warning if the repoVisibility key doesn't have a string value" in:
      val yamlFileContents = "repoVisibility: 1"
      val dir: Path = tempDir()
      val subDir: Path = dir.resolve("test")
      makeDir(subDir)
      write(subDir.resolve("repository.yaml"), yamlFileContents)

      checker.checkVisibilityDefinedCorrectly(dir.toFile, isPrivate = Random.nextBoolean()) shouldBe Some(ParseFailure)

    "check visibility" should:
      "ignore if repository is archived" in:
        val dir: Path = tempDir()
        makeDir(dir.resolve("test"))

        checker.checkVisibility(dir.toFile, Random.nextBoolean(), true) shouldBe None

      "return warnings if repository is not archived" in:
        val dir: Path = tempDir()
        makeDir(dir.resolve("test"))

        checker.checkVisibility(dir.toFile, Random.nextBoolean(), false) shouldBe Some(MissingRepositoryYamlFile)

