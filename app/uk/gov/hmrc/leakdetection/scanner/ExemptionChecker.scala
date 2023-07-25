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

package uk.gov.hmrc.leakdetection.scanner

import com.google.inject.Inject
import uk.gov.hmrc.leakdetection.config.RuleExemption
import uk.gov.hmrc.leakdetection.model.UnusedExemption

class ExemptionChecker @Inject()() {

  def run(matchedResults: Seq[MatchedResult], serviceDefinedExemptions: Seq[RuleExemption]): Seq[UnusedExemption] = {
    val excludedResults = matchedResults.filter(_.isExcluded)

    serviceDefinedExemptions
      .flatMap(e => e.filePaths.map(filePath => UnusedExemption(e.ruleId, filePath, e.text)))
      .filterNot(exemption =>
        excludedResults.exists(exclusion =>
          exemption.ruleId == exclusion.ruleId &&
            normalise(exemption.filePath) == normalise(exclusion.filePath) &&
            exemption.text.fold(true)(exclusion.lineText.contains)
        )
      )
  }

  private def normalise(filePath: String) = filePath match {
    case s if s.startsWith("/") => s.substring(1)
    case _ => filePath
  }
}