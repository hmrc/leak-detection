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

import com.google.inject.Inject
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.{Leak, RepositorySummary, RuleSummary}
import uk.gov.hmrc.leakdetection.persistence.LeakRepository

import scala.concurrent.{ExecutionContext, Future}

class LeaksService @Inject()(ruleService: RuleService,
                             leakRepository: LeakRepository)
                            (implicit ec: ExecutionContext) {

  def getRuleSummaries: Future[Seq[RuleSummary]] = leakRepository.findAllLeaks()
    .map(leaks => {
      val leaksByRule: Map[String, Seq[RepositorySummary]] = leaks.groupBy(_.ruleId)
        .map { case (ruleId, leaksByRule) =>
          (ruleId, leaksByRule.groupBy(_.repoName)
            .map { case (repoName, ruleLeaksByRepo) => RepositorySummary(
              repoName,
              ruleLeaksByRepo.reduce(Ordering.by((_: Leak).timestamp).min).timestamp,
              ruleLeaksByRepo.reduce(Ordering.by((_: Leak).timestamp).max).timestamp,
              ruleLeaksByRepo.length)
            }.toSeq)
        }

      ruleService.getAllRules().map(rule => RuleSummary(rule, leaksByRule.getOrElse(rule.id, Seq())))
    })
}