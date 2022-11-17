/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.implicits._

import java.io.File
import java.{util => ju}
import org.yaml.snakeyaml.Yaml
import play.api.Logger
import uk.gov.hmrc.leakdetection.config.RuleExemption
import uk.gov.hmrc.leakdetection.model.{MissingRepositoryYamlFile, ParseFailure, WarningMessageType}

import scala.io.Source
import scala.jdk.CollectionConverters._

object RulesExemptionParser {

  private val logger = Logger(getClass)

  def parseServiceSpecificExemptions(repoDir: File): Either[WarningMessageType, List[RuleExemption]] =
    try {
      for {
        contents <- getConfigFileContents(repoDir)
        exemptions <- parseYamlAsRuleExemptions(contents)
      } yield exemptions
    } catch {
      case e: RuntimeException =>
        logger.warn(s"Error parsing ${repoDir.getAbsolutePath}/repository.yaml. Ignoring all exemptions.", e)
        Left(ParseFailure)
    }

  private def getConfigFileContents(repoDir: File): Either[WarningMessageType, String] = {
    val f = new File(repoDir.getAbsolutePath + "/" + "repository.yaml")
    if (f.exists) {
      val source = Source.fromFile(f)
      val content = source.mkString
      source.close()
      Right(content)
    } else Left(MissingRepositoryYamlFile)
  }

  private def parseYamlAsRuleExemptions(fileContents: String): Either[WarningMessageType, List[RuleExemption]] = {
    type ExpectedConfigFormat = ju.Map[String, ju.List[ju.Map[String, String]]]

    if(!fileContents.contains("leakDetectionExemptions")) {
      Right(List.empty)
    } else {
      val exemptions: List[ju.Map[String, String]] =
        new Yaml()
          .load(fileContents)
          .asInstanceOf[ExpectedConfigFormat]
          .asScala
          .get("leakDetectionExemptions").map(_.asScala.toList).getOrElse(List.empty)

      exemptions.traverse { entry =>

          def optString(entry: ju.Map[String, String], k: String): Option[String] =
          // we need the cast to String to eagerly ensure that the value actually is a String
            entry.asScala.get(k).map(_.asInstanceOf[String])

          val optRuleId   = optString(entry, "ruleId")
          val optFilePath = optString(entry, "filePath")
          val optText     = optString(entry, "text")
          val filePaths   = entry.asScala
            .getOrElse("filePaths", new java.util.ArrayList())
            .asInstanceOf[java.util.ArrayList[String]]
            .asScala
            .toSeq

          (optRuleId, filePaths.nonEmpty, optFilePath, optText) match {
            case (Some(ruleId), _, Some(fileName), text) => Right(RuleExemption(ruleId, filePaths :+ fileName, text))
            case (Some(ruleId), true, None, text)  => Right(RuleExemption(ruleId, filePaths, text))
            case _ => Left(ParseFailure)
          }
        }
      }
    }
}
