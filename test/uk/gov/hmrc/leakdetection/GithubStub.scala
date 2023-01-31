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

package uk.gov.hmrc.leakdetection

import java.io.{BufferedOutputStream, ByteArrayOutputStream}
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class GithubStub {
  private val config = options.dynamicPort()
  val server         = new WireMockServer(config)

  server.start()

  val port: Int          = server.port()
  val relativeArchiveUrl = "archive-url"
  val archiveUrl         = s"http://localhost:$port/$relativeArchiveUrl"
}

object GithubStub {

  def serving404: GithubStub = {
    val gh = new GithubStub
    gh.server.stubFor(
      get(s"/${gh.relativeArchiveUrl}").willReturn(aResponse().withStatus(404).withBody("404: Not Found"))
    )
    gh
  }

  def servingZippedFiles(files: List[TestZippedFile]): GithubStub = {
    val gh = new GithubStub
    gh.server
      .stubFor(
        get(s"/${gh.relativeArchiveUrl}").willReturn(aResponse().withBody(createZip(files)))
      )
    gh
  }

  def createZip(files: List[TestZippedFile]): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val zos  = new ZipOutputStream(new BufferedOutputStream(baos))

    try {
      files.foreach { file =>
        zos.putNextEntry(new ZipEntry(file.path))
        zos.write(file.content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }

    baos.toByteArray
  }

  case class TestZippedFile(
    content: String,
    path: String = s"repo/${UUID.randomUUID().toString}"
  )

}
