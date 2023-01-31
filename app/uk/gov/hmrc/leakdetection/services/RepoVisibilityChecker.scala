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

import org.yaml.snakeyaml.Yaml
import play.api.Logger
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils
import uk.gov.hmrc.leakdetection.model._

import java.io.File
import java.{util => ju}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class RepoVisibilityChecker {

  private val logger = Logger(getClass)

  val publicVisibilityIdentifier = "public_0C3F0CE3E6E6448FAD341E7BFA50FCD333E06A20CFF05FCACE61154DDBBADF71"
  val privateVisibilityIdentifier = "private_12E5349CFB8BBA30AF464C24760B70343C0EAE9E9BD99156345DD0852C2E0F6F"

  def checkVisibility(dir: File, isPrivate: Boolean, isArchived: Boolean): Option[WarningMessageType] =
    if (isArchived)
      None
    else
      checkVisibilityDefinedCorrectly(dir, isPrivate)

  def checkVisibilityDefinedCorrectly(dir: File, isPrivate: Boolean): Option[WarningMessageType] =
    try {
      val projectRoot = FileAndDirectoryUtils.getSubdirName(dir)

      val repositoryYaml: File = new File(projectRoot.getAbsolutePath.concat("/repository.yaml"))

      if (!repositoryYaml.exists()) {
        logger.warn(s"$repositoryYaml file not found")
        Some(MissingRepositoryYamlFile)
      } else {

        val fileSource = Source.fromFile(repositoryYaml)
        val fileContents = try {
          fileSource.mkString
        } catch {
          case ex: Exception =>
            logger.error("failed to read repository.yaml", ex)
            throw ex
        } finally {
          fileSource.close()
        }

        type ExpectedConfigFormat = ju.Map[String, String]

        val repoVisibility = new Yaml()
          .load(fileContents)
          .asInstanceOf[ExpectedConfigFormat]
          .asScala
          .get("repoVisibility")

        repoVisibility match {
          case Some(value) =>
            if (isPrivate && value == privateVisibilityIdentifier)
              None
            else if (!isPrivate && value == publicVisibilityIdentifier)
              None
            else {
              logger.warn(s"Invalid value of repoVisibility entry: [$value], repo privacy [$isPrivate]")
              Some(InvalidEntry)
            }
          case None =>
            logger.warn("Missing repoVisibility entry")
            Some(MissingEntry)
        }
      }
    } catch {
      case NonFatal(ex) =>
        logger.warn("Failed to parse repository.yaml to assert repoVisibility", ex)
        Some(ParseFailure)
    }
}
