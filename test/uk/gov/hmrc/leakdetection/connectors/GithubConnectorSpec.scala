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

package uk.gov.hmrc.leakdetection.connectors

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.leakdetection.model.Branch


class GithubConnectorSpec extends AnyFlatSpec with Matchers:
  "Branch names" should "be url-encoded" in:
    val nonEscapedBranchName = "feature/#10_DeathToConcrete" // real life example

    val result =
      GithubConnector.getArtifactUrl(
        "https://api.github.com/repos/hmrc/github-link{/ref}",
        Branch(nonEscapedBranchName)
      )

    result.toString shouldBe "https://api.github.com/repos/hmrc/github-link/refs/heads/feature%2F%2310_DeathToConcrete"
