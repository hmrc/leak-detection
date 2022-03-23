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

package uk.gov.hmrc.leakdetection.scanner

import com.google.inject.Inject
import play.api.libs.json.Json
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils
import uk.gov.hmrc.leakdetection.services.RulesExemptionParser

import java.io.File

class ExemptionChecker @Inject()() {

  def run(matchedResults: Seq[MatchedResult], explodedZipDir: File): Seq[UnusedExemption] = {
    val excludedResults = matchedResults.filter(_.isExcluded)

    RulesExemptionParser
      .parseServiceSpecificExemptions(FileAndDirectoryUtils.getSubdirName(explodedZipDir))
      .flatMap(e => e.filePaths.map(filePath => UnusedExemption(e.ruleId, filePath, e.text)))
      .filterNot(
        exemption =>
          excludedResults.exists(
            exclusion =>
              exemption.ruleId == exclusion.ruleId &&
                exemption.filePath == exclusion.filePath &&
                exemption.text.fold(true)(exclusion.lineText.contains)))
  }
}

final case class UnusedExemption(
                                  ruleId: String,
                                  filePath: String,
                                  text: Option[String]
                                )

object UnusedExemption {
  implicit val format = Json.format[UnusedExemption]
}
