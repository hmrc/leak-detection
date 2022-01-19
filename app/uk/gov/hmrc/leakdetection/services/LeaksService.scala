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
import uk.gov.hmrc.leakdetection.config.{ConfigLoader, Rule}
import uk.gov.hmrc.leakdetection.model.{RuleSummary, ViolationsSummary}

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.{ExecutionContext, Future}

class ViolationsService @Inject()(configLoader: ConfigLoader)
                                 (implicit ec: ExecutionContext) {

  def getRuleSummary = Future.successful(
    Seq(
      RuleSummary(
        Rule("aws_secret_access_key",
          Rule.Scope.FILE_CONTENT,
          """(SECRET|secret|Secret|ACCESS|access|Access|KEY|key|Key)("|')?(:.{0,50})?\s*(:|=>|=|->)\s*("|')?[A-Za-z0-9\/\+=]{40}(?![A-Za-z0-9\/+=])""",
          "AWS secret key",
          priority = "high",
          ignoredFiles = List("/conf/application.conf", "^\\/.*phantomjs.*", "^\\/.*chromedriver.*", "^\\/.*BrowserStackLocal.*", "/repository.yaml"),
          ignoredExtensions = List(".tar", ".gz", ".jar", ".7z", ".rar", ".bz2", ".zip", ".gzip", ".war", ".ear",
            ".xlsx", ".xls", ".docx", ".doc", ".pptx", ".pdf",
            ".jpg", ".png", ".jpeg", ".tif", ".tiff", ".gif", ".bmp", ".webp", ".svg", ".ico", ".psd",
            ".exe", ".dll", ".dmg", ".deb", ".rpm")),
        Seq(ViolationsSummary("leak-detection", Instant.now, 2))
      ),
      RuleSummary(
        Rule("cert_1",
          Rule.Scope.FILE_CONTENT,
          """-----(BEGIN|END).*?PRIVATE.*?-----""",
          "certificates and private keys",
          priority = "low",
          ignoredFiles = List("^\\/.*phantomjs.*", "^\\/.*chromedriver.*", "^\\/.*BrowserStackLocal.*", """/\.*kitchen\.yml""", "/repository.yaml"),
          ignoredExtensions = List(".tar", ".gz", ".jar", ".7z", ".rar", ".bz2", ".zip", ".gzip", ".war", ".ear",
            ".xlsx", ".xls", ".docx", ".doc", ".pptx", ".pdf",
            ".jpg", ".png", ".jpeg", ".tif", ".tiff", ".gif", ".bmp", ".webp", ".svg", ".ico", ".psd",
            ".exe", ".dll", ".dmg", ".deb", ".rpm")),
        Seq(
          ViolationsSummary("leak-detection", Instant.now.minus(1, HOURS), 3),
          ViolationsSummary("leak-detection-frontend", Instant.now.minus(3, HOURS), 2)
        )
      ),
      RuleSummary(
        Rule("filename_private_key_1",
          Rule.Scope.FILE_NAME,
          """.p12\z""",
          "File often containing private keys",
          priority = "medium"),
        Seq()
      ),
      RuleSummary(
        Rule("message_delivery_group_key",
          Rule.Scope.FILE_CONTENT,
        """\b([0-9a-z]{20,40}(pre)?prod)\b""",
        "Message Delievery Group key",
        ignoredFiles = List("/repository.yaml"),
        priority = "low"),
        Seq())
    )
  )

}
