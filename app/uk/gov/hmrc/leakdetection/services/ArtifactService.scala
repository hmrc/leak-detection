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
import java.net.URLEncoder
import java.nio.file.Files

import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import org.apache.commons.io.FileUtils
import org.zeroturnaround.zip.ZipUtil
import play.api.Logger
import scalaj.http._

import cats.implicits._

import scala.language.postfixOps

class ArtifactService @Inject()(metrics: Metrics) {

  import ArtifactService._

  val logger = Logger("ArtifactManager")

  private lazy val registry = metrics.defaultRegistry

  def getZipAndExplode(
    githubPersonalAccessToken: String,
    archiveUrl: String,
    branchRef: String): Either[BranchNotFound, ExplodedZip] = {

    logger.info("starting zip process....")
    val savedZipFilePath = Files.createTempDirectory("unzipped_").toString
    val downloadResult   = getZip(githubPersonalAccessToken, archiveUrl, branchRef, savedZipFilePath)
    downloadResult.map { _ =>
      explodeZip(savedZipFilePath)
    }

  }

  def getZip(
    githubPersonalAccessToken: String,
    archiveUrl: String,
    branch: String,
    savedZipFilePath: String): Either[BranchNotFound, DownloadedZip] = {
    val githubZipUri = getArtifactUrl(archiveUrl, branch)
    logger.info(s"Getting code archive from: $githubZipUri")

    downloadFile(githubPersonalAccessToken, githubZipUri, savedZipFilePath, branch)
  }

  def explodeZip(savedZipFilePath: String): ExplodedZip = {
    val explodedZipFile = new File(savedZipFilePath)
    ZipUtil.explode(explodedZipFile)
    logger.info(s"Zip file exploded successfully")
    ExplodedZip(explodedZipFile)
  }

  def downloadFile(
    githubAccessToken: String,
    url: String,
    filename: String,
    branch: String): Either[BranchNotFound, DownloadedZip] = {
    val resp =
      Http(url)
        .header("Authorization", s"token $githubAccessToken")
        .option(HttpOptions.followRedirects(true))
        .asBytes
    if (resp.code == 404) {
      Left(BranchNotFound(branch))
    } else if (resp.isError) {
      registry.counter(s"github.open.zip.failure").inc()
      val errorMessage = s"Error downloading the zip file from $url:\n${new String(resp.body)}"
      logger.error(errorMessage)
      throw new RuntimeException(errorMessage)
    } else {
      registry.counter(s"github.open.zip.success").inc()
      logger.info(s"Response code: ${resp.code}")
      logger.debug(s"Got ${resp.body.length} bytes from $url... saving it to $filename")
      val file = new File(filename)
      FileUtils.deleteQuietly(file)
      FileUtils.writeByteArrayToFile(file, resp.body)
      logger.info(s"Saved file: $filename")
      Right(DownloadedZip(file))
    }
  }

  def getArtifactUrl(archiveUrl: String, branch: String): String = {
    val urlEncodedBranchName = URLEncoder.encode(branch, "UTF-8")
    archiveUrl.replace("{archive_format}", "zipball").replace("{/ref}", s"/$urlEncodedBranchName")
  }
}

object ArtifactService {
  final case class BranchNotFound(branchName: String)

  final case class DownloadedZip(file: File)
  final case class ExplodedZip(dir: File)
}
