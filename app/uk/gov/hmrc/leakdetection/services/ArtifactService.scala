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
import com.kenshoo.play.metrics.Metrics
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

  def getZip(
    githubAccessToken: String,
    archiveUrl: String,
    branch: Branch
  ): Future[Either[BranchNotFound, File]] = {
    logger.info("starting zip process....")
    val zipUrl = getArtifactUrl(archiveUrl, branch)
    logger.info(s"Getting code archive from: $zipUrl")
    ws.url(zipUrl)
      .addHttpHeaders("Authorization" -> s"token $githubAccessToken")
      .withFollowRedirects(true)
      .withMethod("GET")
      .stream() flatMap {
      response => response.status match {
        case 200 =>
          registry.counter(s"github.open.zip.success").inc()
          val savedZipFilePath = Files.createTempFile("unzipped_", "")
          logger.debug(s"Got ${response.body.length} bytes from $zipUrl... saving it to ${savedZipFilePath.toString}")
          response.bodyAsSource.runWith(FileIO.toPath(savedZipFilePath)) map { _ =>
            logger.info(s"Saved file: ${savedZipFilePath.toString}")
            ZipUtil.explode(savedZipFilePath.toFile)
            Right(savedZipFilePath.toFile)
          }
        case 404 =>
          Future.successful(Left(BranchNotFound(branch)))
        case status =>
          registry.counter(s"github.open.zip.failure").inc()
          throw new RuntimeException(s"Error downloading the zip file from $zipUrl received status $status:\n${new String(response.body)}")
      }
    }
  }
}

object ArtifactService {
  final case class BranchNotFound(branchName: Branch)

  def getArtifactUrl(archiveUrl: String, branch: Branch): String = {
    val urlEncodedBranchName = URLEncoder.encode(branch.asString, "UTF-8")
    archiveUrl.replace("{archive_format}", "zipball").replace("{/ref}", s"/refs/heads/$urlEncodedBranchName")
  }
}
