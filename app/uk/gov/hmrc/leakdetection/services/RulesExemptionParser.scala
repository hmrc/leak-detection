/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.leakdetection.config.RuleExemption

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try

object RulesExemptionParser {

  def parseServiceSpecificExemptions(repoDir: File): List[RuleExemption] =
    getConfigFileContents(repoDir).map(parseYamlAsRuleExemptions).getOrElse(Nil)

  private def getConfigFileContents(repoDir: File): Option[String] = {
    val f = new File(repoDir.getAbsolutePath + "/" + "repository.yaml")
    if (f.exists) {
      Some(Source.fromFile(f).mkString)
    } else None
  }

  private def parseYamlAsRuleExemptions(fileContents: String): List[RuleExemption] = {
    type ExpectedConfigFormat = ju.Map[String, ju.List[ju.Map[String, String]]]
    val maybeRawConfig = Try {
      Option(new Yaml().load(fileContents).asInstanceOf[ExpectedConfigFormat])
    }.toOption.flatten

    maybeRawConfig.map { rawConfig =>
      rawConfig.asScala
        .get("leakDetectionExemptions")
        .map { list =>
          list.asScala.flatMap { entry =>
            for {
              ruleId   <- entry.asScala.get("ruleId")
              fileName <- entry.asScala.get("filePath")
            } yield {
              RuleExemption(ruleId, fileName)
            }
          }
        }
        .getOrElse(Nil)
        .toList
    }
  }.getOrElse(Nil)

}
