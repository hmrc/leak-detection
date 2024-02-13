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

import com.codahale.metrics.MetricRegistry
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.zeroturnaround.zip.ZipUtil
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.leakdetection.model.{Branch, GitBlame, Repository}

import java.io.File
import java.net.URL
import java.nio.file.Path
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GithubConnector @Inject()(
  config         : Configuration,
  httpClientV2   : HttpClientV2,
  metricsRegistry: MetricRegistry
)(implicit
  ec : ExecutionContext,
  mat: Materializer
) extends Logging {
  import GithubConnector._
  import HttpReads.Implicits._

  private val githubToken        = config.get[String  ]("githubSecrets.personalAccessToken")
  private val githubUrl          = config.get[String  ]("github.url")
  private val zipDownloadTimeout = config.get[Duration]("github.zipDownloadTimeout")
  private val zipDownloadMaxSize = config.get[Int     ]("github.zipDownloadMaxSize")


  implicit private val hc: HeaderCarrier = HeaderCarrier()

  def getRateLimit(): Future[JsValue] =
     httpClientV2
      .get(url"$githubUrl/rate_limit")
      .setHeader("Authorization" -> s"token $githubToken")
      .withProxy
      .execute[JsValue]

  private val preventLargeDownloads = {
    val count = new java.util.concurrent.atomic.AtomicInteger()
    Sink
      .foreach[ByteString] { bs =>
        val mbs = count.updateAndGet(_ + (bs.length / 1000000))

        if (mbs >= zipDownloadMaxSize) throw new LargeDownloadException(s"Download stopped after: $mbs MBs because over max size: $zipDownloadMaxSize MBs")
        else                           ()
      }
  }

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
      .transform(_.withRequestTimeout(zipDownloadTimeout))
      .stream[Either[UpstreamErrorResponse, Source[ByteString, _]]]
      .flatMap {
        case Right(source) =>
          metricsRegistry.counter(s"github.open.zip.success").inc()
          logger.debug(s"Saving $archiveUrl to $savedZipFilePath")
          source
            .alsoToMat(preventLargeDownloads)(Keep.none)
            .runWith(FileIO.toPath(savedZipFilePath))
            .map { _ =>
              val savedZipFile = savedZipFilePath.toFile
              logger.info(s"Saved file: ${savedZipFilePath}")
              ZipUtil.explode(savedZipFile)
              logger.info(s"zip process complete, free disk space ${savedZipFilePath.toFile.getFreeSpace}")
              Right(savedZipFile)
            }
        case Left(UpstreamErrorResponse.WithStatusCode(404)) =>
          Future.successful(Left(BranchNotFound(branch)))
        case Left(error) =>
          metricsRegistry.counter(s"github.open.zip.failure").inc()
          Future.failed(new RuntimeException(s"Error downloading the zip file from $zipUrl received status ${error.statusCode}", error))
      }
  }

  def getBlame(repository: Repository, branch: Branch, file: String): Future[GitBlame] = {
    val blameQuery = getBlameQuery
      .withVariable("repo", JsString(repository.asString))
      .withVariable("branch", JsString(branch.asString))
      .withVariable("file", JsString(if (file.startsWith("/")) file.substring(1) else file))
    httpClientV2
      .post(url"$githubUrl/graphql")
      .withBody(blameQuery.asJson)
      .setHeader("Authorization" -> s"token $githubToken")
      .withProxy
      .execute[GitBlame]
  }
}


object GithubConnector {

  case class LargeDownloadException(message: String) extends Exception(message)

  final case class BranchNotFound(name: Branch)

  def getArtifactUrl(archiveUrl: String, branch: Branch): URL = {
    val urlEncodedBranchName = java.net.URLEncoder.encode(branch.asString, "UTF-8")
    new URL(archiveUrl.replace("{archive_format}", "zipball").replace("{/ref}", s"/refs/heads/$urlEncodedBranchName"))
  }

  val getBlameQuery: GraphqlQuery =
    GraphqlQuery(
      s"""
        query($$repo: String!, $$branch: String, $$file: String!) {
          repositoryOwner(login: "hmrc") {
            repository(name: $$repo) {
              object(expression: $$branch) {
                ... on Commit {
                  blame(path: $$file) {
                    ranges {
                      startingLine
                      endingLine
                      age
                      commit {
                        oid
                        author {
                          name
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      """
    )
}

final case class GraphqlQuery(
  query: String,
  variables: Map[String, JsValue] = Map.empty) {

  def withVariable(name: String, value: JsValue): GraphqlQuery =
    copy(variables = variables + (name -> value))

  def asJson: JsValue =
    JsObject(
      Map(
        "query" -> JsString(query),
        "variables" -> JsObject(variables)
      )
    )

  def asJsonString: String =
    Json.stringify(asJson)
}

