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

import java.io.File
import java.{util => ju}

import org.yaml.snakeyaml.Yaml
import play.api.Logger
import uk.gov.hmrc.leakdetection.config.RuleExemption

import scala.io.Source
import scala.jdk.CollectionConverters._

object RulesExemptionParser {

  private val logger = Logger(getClass)

  def parseServiceSpecificExemptions(repoDir: File): List[RuleExemption] =
    try {
      getConfigFileContents(repoDir).map(parseYamlAsRuleExemptions).getOrElse(Nil)
    } catch {
      case e: RuntimeException =>
        logger.warn(s"Error parsing ${repoDir.getAbsolutePath}/repository.yaml. Ignoring all exemptions.", e)
        List.empty
    }

  def getConfigFileContents(repoDir: File): Option[String] = {
    val f = new File(repoDir.getAbsolutePath + "/" + "repository.yaml")
    if (f.exists) {
      val source  = Source.fromFile(f)
      val content = source.mkString
      source.close()
      Some(content)
    } else None
  }

  private def parseYamlAsRuleExemptions(fileContents: String): List[RuleExemption] = {
    type ExpectedConfigFormat = ju.Map[String, ju.List[ju.Map[String, String]]]

    new Yaml()
      .load(fileContents)
      .asInstanceOf[ExpectedConfigFormat]
      .asScala
      .get("leakDetectionExemptions")
      .map { entries =>
        def getString(entry: ju.Map[String, String], k: String): Option[String] =
          // we need the cast to String to eagerly ensure that the value actually is a String
          entry.asScala.get(k).map(_.asInstanceOf[String])
        entries.asScala.flatMap { entry =>
          val ruleIdO   = getString(entry, "ruleId")
          val fileNameO = getString(entry, "filePath")
          val fileNames = entry.asScala
            .getOrElse("filePaths", new java.util.ArrayList())
            .asInstanceOf[java.util.ArrayList[String]]
            .asScala
            .toSeq
          val text = getString(entry, "text")

          (ruleIdO, fileNames, fileNameO, text) match {
            case (Some(ruleId), _, Some(fileName), text)             => Some(RuleExemption(ruleId, fileNames :+ fileName, text))
            case (Some(ruleId), files, None, text) if files.nonEmpty => Some(RuleExemption(ruleId, fileNames, text))
            case _                                                   => None
          }
        }
      }
      .getOrElse(Nil)
      .toList
  }
}
