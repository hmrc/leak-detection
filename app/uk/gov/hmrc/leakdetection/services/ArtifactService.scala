/*
 * Copyright 2021 HM Revenue & Customs
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

import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import cats.data.EitherT
import cats.implicits._
import com.kenshoo.play.metrics.Metrics
import org.apache.commons.io.FileUtils
import org.zeroturnaround.zip.ZipUtil
import play.api.Logger
import play.api.libs.ws._
import uk.gov.hmrc.leakdetection.model.Branch

import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ArtifactService @Inject()(ws: WSClient, metrics: Metrics)(implicit  ec: ExecutionContext, mat: Materializer) {

  import ArtifactService._

  private val logger = Logger(this.getClass.getName)

  private lazy val registry = metrics.defaultRegistry

  def getZipAndExplode(
    githubPersonalAccessToken: String,
    archiveUrl: String,
    branch: Branch
  ): Future[Either[BranchNotFound, ExplodedZip]] = {
    logger.info("starting zip process....")
    val savedZipFilePath = Files.createTempDirectory("unzipped_").toString
    val downloadResult = EitherT(getZip(githubPersonalAccessToken, archiveUrl, branch, savedZipFilePath))
    val explodedZip = downloadResult map {
      _ =>
        explodeZip(savedZipFilePath)
    }
    explodedZip.value
  }

  def getZip(
    githubPersonalAccessToken: String,
    archiveUrl: String,
    branch: Branch,
    savedZipFilePath: String
  ): Future[Either[BranchNotFound, DownloadedZip]] = {
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
    branch: Branch
  ): Future[Either[BranchNotFound, DownloadedZip]] = {
    ws.url(url)
      .addHttpHeaders("Authorization" -> s"token $githubAccessToken")
      .withFollowRedirects(true)
      .withMethod("GET")
      .stream() flatMap {
      response =>
        if (response.status == 404) {
          Future.successful(Left(BranchNotFound(branch)))
        } else if (response.status != 200) {
          registry.counter(s"github.open.zip.failure").inc()
          val errorMessage = s"Error downloading the zip file from $url:\n${new String(response.body)}"
          logger.error(errorMessage)
          throw new RuntimeException(errorMessage)
        } else {
          registry.counter(s"github.open.zip.success").inc()
          logger.info(s"Response code: ${response.status}")
          logger.debug(s"Got ${response.body.length} bytes from $url... saving it to $filename")
          val file = new File(filename)
          FileUtils.deleteQuietly(file)
          response.bodyAsSource.runWith(FileIO.toPath(file.toPath)) map { _ =>
            logger.info(s"Saved file: $filename")
            Right(DownloadedZip(file))
          }
        }
    }
  }
}

object ArtifactService {
  final case class BranchNotFound(branchName: Branch)

  final case class DownloadedZip(file: File)
  final case class ExplodedZip(dir: File)

  def getArtifactUrl(archiveUrl: String, branch: Branch): String = {
    val urlEncodedBranchName = URLEncoder.encode(branch.asString, "UTF-8")
    archiveUrl.replace("{archive_format}", "zipball").replace("{/ref}", s"/refs/heads/$urlEncodedBranchName")
  }
}
