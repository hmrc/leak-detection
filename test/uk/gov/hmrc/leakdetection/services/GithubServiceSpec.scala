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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, Upstream4xxResponse, Upstream5xxResponse}
import uk.gov.hmrc.http.test.{ExternalWireMockSupport, HttpClientSupport}
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, PlayConfigLoader}
import uk.gov.hmrc.leakdetection.model.{Branch, Repository}

class GithubServiceSpec extends AsyncWordSpec with Matchers with ExternalWireMockSupport with HttpClientSupport {

  private val githubAccessToken = "ACCESS"
  private val repository = "repo"
  private implicit val hc: HeaderCarrier = HeaderCarrier()


  private val defaultConfiguration = Configuration(
    "githubSecrets.personalAccessToken"      -> githubAccessToken,
    "githubSecrets.webhookSecretKey"         -> "PLACEHOLDER",
    "github.url"                             -> externalWireMockUrl,
    "github.apiUrl"                          -> externalWireMockUrl,
    "allRules.privateRules"                  -> List(),
    "allRules.publicRules"                   -> List(),
    "leakResolutionUrl"                      -> "PLACEHOLDER",
    "maxLineLength"                          -> 2147483647,
    "clearingCollectionEnabled"              -> false
  )
  private lazy val configLoader: ConfigLoader = new PlayConfigLoader(defaultConfiguration)


  "getDefaultBranchName" should {
    "return default branch name" in {
        externalWireMockServer.stubFor(get(s"/$repository")
          .willReturn(okJson("""{"default_branch": "default1"}""")))
      val service = new GithubService(httpClient, configLoader)
      service.getDefaultBranchName(Repository("repo")) map { branchName => branchName shouldBe Branch("default1")}
    }
    "return main when github sends 404 response" in {
      externalWireMockServer.stubFor(get(s"/$repository")
        .willReturn(notFound()))
      val service = new GithubService(httpClient, configLoader)
      service.getDefaultBranchName(Repository("repo")) map { branchName => branchName shouldBe Branch.main}
    }
    "return main when github sends 403 response" in {
      externalWireMockServer.stubFor(get(s"/$repository")
        .willReturn(forbidden()))
      val service = new GithubService(httpClient, configLoader)
      service.getDefaultBranchName(Repository("repo")).failed map { _ shouldBe an[Upstream4xxResponse] }
    }
    "return main when github sends 503 response" in {
      externalWireMockServer.stubFor(get(s"/$repository")
        .willReturn(serviceUnavailable()))
      val service = new GithubService(httpClient, configLoader)
      service.getDefaultBranchName(Repository("repo")).failed map { _ shouldBe an[Upstream5xxResponse] }
    }
  }
}
