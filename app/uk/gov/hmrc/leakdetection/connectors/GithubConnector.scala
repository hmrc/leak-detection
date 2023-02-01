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

import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.kenshoo.play.metrics.Metrics
import org.zeroturnaround.zip.ZipUtil
import play.api.libs.json.JsValue
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.leakdetection.model.Branch

import java.io.File
import java.net.URL
import java.nio.file.Path
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GithubConnector @Inject()(
  config      : Configuration,
  httpClientV2: HttpClientV2,
  metrics     : Metrics
)(implicit
  ec : ExecutionContext,
  mat: Materializer
) extends Logging {
  import GithubConnector._
  import HttpReads.Implicits._

  private val githubToken = config.get[String]("githubSecrets.personalAccessToken")
  private val githubUrl   = config.get[String]("github.url")

  private lazy val registry = metrics.defaultRegistry

  implicit private val hc = HeaderCarrier()

  def getRateLimit(): Future[JsValue] =
     httpClientV2
      .get(url"$githubUrl/rate_limit")
      .setHeader("Authorization" -> s"token $githubToken")
      .withProxy
      .execute[JsValue]

  def getZip(
    archiveUrl      : String,
    branch          : Branch,
    savedZipFilePath: Path
  ): Future[Either[BranchNotFound, File]] = {
    logger.info(s"starting zip process, free disk space ${savedZipFilePath.toFile.getFreeSpace}")
    val zipUrl = getArtifactUrl(archiveUrl, branch)
    logger.info(s"Getting code archive from: $zipUrl")
    httpClientV2
      .get(zipUrl)
      .setHeader("Authorization" -> s"token $githubToken")
      .withProxy
      .stream[Either[UpstreamErrorResponse, Source[ByteString, _]]]
      .flatMap {
        case Right(source) =>
          registry.counter(s"github.open.zip.success").inc()

          logger.debug(s"Saving $archiveUrl to $savedZipFilePath")
          source.runWith(FileIO.toPath(savedZipFilePath)).map { _ =>
            val savedZipFile = savedZipFilePath.toFile
            logger.info(s"Saved file: ${savedZipFilePath}")
            ZipUtil.explode(savedZipFile)
            logger.info(s"zip process complete, free disk space ${savedZipFilePath.toFile.getFreeSpace}")
            Right(savedZipFile)
          }
        case Left(UpstreamErrorResponse.WithStatusCode(404)) =>
          Future.successful(Left(BranchNotFound(branch)))
        case Left(error) =>
          registry.counter(s"github.open.zip.failure").inc()
          Future.failed(new RuntimeException(s"Error downloading the zip file from $zipUrl received status ${error.statusCode}", error))
      }
  }
}

final case class BranchNotFound(branchName: Branch)

object GithubConnector {
  import java.net.URLEncoder

  def getArtifactUrl(archiveUrl: String, branch: Branch): URL = {
    val urlEncodedBranchName = URLEncoder.encode(branch.asString, "UTF-8")
    new URL(archiveUrl.replace("{archive_format}", "zipball").replace("{/ref}", s"/refs/heads/$urlEncodedBranchName"))
  }
}
