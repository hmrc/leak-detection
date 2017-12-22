/*
 * Copyright 2017 HM Revenue & Customs
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
import java.nio.file.Files

import org.apache.commons.io.FileUtils
import org.zeroturnaround.zip.ZipUtil
import play.api.Logger

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scalaj.http._

class ArtifactService() {
  val logger = Logger("ArtifactManager")

  def getZipAndExplode(githubPersonalAccessToken: String, archiveUrl: String, branchRef: String): File = {
    logger.info("starting zip process....")
    val savedZipFilePath = Files.createTempDirectory("unzipped_").toString
    getZip(githubPersonalAccessToken, archiveUrl, branchRef, savedZipFilePath)
    explodeZip(savedZipFilePath)
  }

  def getZip(githubPersonalAccessToken: String, archiveUrl: String, branch: String, savedZipFilePath: String): Unit = {
    val githubZipUri = getArtifactUrl(archiveUrl, branch)
    logger.info(s"Getting code archive from: $githubZipUri")

    downloadFile(githubPersonalAccessToken, githubZipUri, savedZipFilePath, branch)
    logger.info(s"saved archive to: $savedZipFilePath")
  }

  def explodeZip(savedZipFilePath: String): File = {
    val explodedZipFile = new File(savedZipFilePath)
    ZipUtil.explode(explodedZipFile)
    logger.info(s"Zip file exploded successfully")
    explodedZipFile
  }

  def downloadFile(githubAccessToken: String, url: String, filename: String, branch: String): Unit =
    retry(5) {
      val resp =
        Http(url)
          .header("Authorization", s"token $githubAccessToken")
          .option(HttpOptions.followRedirects(true))
          .asBytes
      if (resp.isError) {
        val errorMessage = s"Error downloading the zip file from $url:\n${new String(resp.body)}"
        logger.error(errorMessage)
        throw new RuntimeException(errorMessage)
      } else {
        logger.info(s"Response code: ${resp.code}")
        logger.debug(s"Got ${resp.body.size} bytes from $url... saving it to $filename")
        val file = new File(filename)
        FileUtils.deleteQuietly(file)
        FileUtils.writeByteArrayToFile(file, resp.body)
        logger.info(s"Saved file: $filename")
      }
    }

  def retry[T](retryCount: Int)(f: => T): T =
    Try(f) match {
      case Success(resp) => resp
      case Failure(t) =>
        if (retryCount > 0) {
          Thread.sleep(200)
          retry(retryCount - 1)(f)
        } else throw t
    }

  private def getArtifactUrl(archiveUrl: String, branch: String) =
    archiveUrl.replace("{archive_format}", "zipball").replace("{/ref}", s"/$branch")
}
