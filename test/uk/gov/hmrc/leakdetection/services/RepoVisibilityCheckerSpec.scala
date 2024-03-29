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

import ammonite.ops.{Path, mkdir, tmp, write}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.model.{InvalidEntry, MissingEntry, MissingRepositoryYamlFile, ParseFailure}

import scala.util.Random

class RepoVisibilityCheckerSpec extends AnyWordSpec with Matchers {

  "repositoryYamlChecker" should {

    "return a warning if the repository.yaml file is not found" in {
      val dir: Path = tmp.dir()
      mkdir(dir / "test")
      val checker = new RepoVisibilityChecker()

      checker.checkVisibilityDefinedCorrectly(dir.toIO, Random.nextBoolean()) shouldBe Some(MissingRepositoryYamlFile)
    }

    "confirm if repoVisibility has correct value for a public repository" in {
      val yamlFileContents = "repoVisibility: public_0C3F0CE3E6E6448FAD341E7BFA50FCD333E06A20CFF05FCACE61154DDBBADF71"
      val dir: Path = tmp.dir()
      val subDir: Path = dir / "test"
      mkdir(subDir)
      write(subDir / "repository.yaml", yamlFileContents)

      val checker = new RepoVisibilityChecker()

      checker.checkVisibilityDefinedCorrectly(dir.toIO, isPrivate = false) shouldBe None
    }

    "confirm if repoVisibility has correct value for a private repository" in {
      val yamlFileContents = "repoVisibility: private_12E5349CFB8BBA30AF464C24760B70343C0EAE9E9BD99156345DD0852C2E0F6F"
      val dir: Path = tmp.dir()
      val subDir: Path = dir / "test"
      mkdir(subDir)
      write(subDir / "repository.yaml", yamlFileContents)

      val checker = new RepoVisibilityChecker()

      checker.checkVisibilityDefinedCorrectly(dir.toIO, isPrivate = true) shouldBe None
    }

    "return a warning if the repoVisibility value is incorrect" in {
      val yamlFileContents = "repoVisibility: private_12E5349CFB8BBA30AF464C24760B70343C0EAE9E9BD99156345DD0852C2E0F6F"
      val dir: Path = tmp.dir()
      val subDir: Path = dir / "test"
      mkdir(subDir)
      write(subDir / "repository.yaml", yamlFileContents)

      val checker = new RepoVisibilityChecker()

      checker.checkVisibilityDefinedCorrectly(dir.toIO, false) shouldBe Some(InvalidEntry)
    }

    "return a warning if the file doesn't have the repoVisibility key" in {
      val yamlFileContents = "foo: bar"
      val dir: Path = tmp.dir()
      val subDir: Path = dir / "test"
      mkdir(subDir)
      write(subDir / "repository.yaml", yamlFileContents)

      val checker = new RepoVisibilityChecker()

      checker.checkVisibilityDefinedCorrectly(dir.toIO, isPrivate = Random.nextBoolean()) shouldBe Some(MissingEntry)
    }

    "return a warning if the repoVisibility key doesn't have a string value" in {
      val yamlFileContents = "repoVisibility: 1"
      val dir: Path = tmp.dir()
      val subDir: Path = dir / "test"
      mkdir(subDir)
      write(subDir / "repository.yaml", yamlFileContents)

      val checker = new RepoVisibilityChecker()

      checker.checkVisibilityDefinedCorrectly(dir.toIO, isPrivate = Random.nextBoolean()) shouldBe Some(ParseFailure)
    }

    "check visibility" should {
      "ignore if repository is archived" in {
        val dir: Path = tmp.dir()
        mkdir(dir / "test")
        val checker = new RepoVisibilityChecker()

        checker.checkVisibility(dir.toIO, Random.nextBoolean(), true) shouldBe None
      }

      "return warnings if repository is not archived" in {
        val dir: Path = tmp.dir()
        mkdir(dir / "test")
        val checker = new RepoVisibilityChecker()

        checker.checkVisibility(dir.toIO, Random.nextBoolean(), false) shouldBe Some(MissingRepositoryYamlFile)
      }
    }
  }
}
