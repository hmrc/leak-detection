/*
 * Copyright 2020 HM Revenue & Customs
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

import scala.collection.JavaConverters._
import scala.io.Source

object RulesExemptionParser {

  def parseServiceSpecificExemptions(repoDir: File): List[RuleExemption] =
    try { getConfigFileContents(repoDir).map(parseYamlAsRuleExemptions).getOrElse(Nil) } catch {
      case e: RuntimeException =>
        Logger.warn(s"Error parsing ${repoDir.getAbsolutePath}/repository.yaml. Ignoring all exemptions.", e)
        List.empty
    }

  private def getConfigFileContents(repoDir: File): Option[String] = {
    val f = new File(repoDir.getAbsolutePath + "/" + "repository.yaml")
    if (f.exists) {
      Some(Source.fromFile(f).mkString)
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
        entries.asScala.flatMap { entry =>
          val ruleIdO   = entry.asScala.get("ruleId")
          val fileNameO = entry.asScala.get("filePath")
          val fileNames =
            entry.asScala
              .getOrElse("filePaths", new java.util.ArrayList())
              .asInstanceOf[java.util.ArrayList[String]]
              .asScala

          (ruleIdO, fileNames, fileNameO) match {
            case (Some(ruleId), _, Some(fileName)) => Some(RuleExemption(ruleId, fileNames :+ fileName))
            case (Some(ruleId), _, None)           => Some(RuleExemption(ruleId, fileNames))
            case (None, _, _)                      => None
          }
        }
      }
      .getOrElse(Nil)
      .toList
  }

}
