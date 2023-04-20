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

package uk.gov.hmrc.leakdetection.scanner

import play.api.Logger
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption, SecretHashConfig}
import uk.gov.hmrc.leakdetection.services.{InMemorySecretHashChecker, MongoSecretHashChecker, SecretHashChecker}

import java.io.File
import java.nio.charset.CodingErrorAction
import scala.collection.parallel.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.{Codec, Source}

case class Result(filePath: String, scanResults: MatchedResult)

class RegexMatchingEngine(rules: List[Rule], maxLineLength: Int, secretHashConfig: SecretHashConfig, secretHashChecker: SecretHashChecker)
                         (implicit ec: ExecutionContext){

  import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils._

  private val logger = Logger(getClass)

  val fileContentScanners = createFileContentScanners(rules)
  val fileNameScanners    = createFileNameScanners(rules)
  val fileExtensionR      = """\.[A-Za-z0-9]+$""".r
  val secretHashScanner   = new SecretHashScanner(secretHashConfig, secretHashChecker)


  implicit val codec = Codec("UTF-8")
  codec.onMalformedInput(CodingErrorAction.IGNORE)
  codec.onUnmappableCharacter(CodingErrorAction.IGNORE)

  def secretsScanner(file: File, filePath: String, serviceDefinedExemptions: List[RuleExemption]): Future[List[MatchedResult]] = {
    val source = Source.fromFile(file)

    val hashResults = try {
      source.getLines().foldLeft((1, Future.successful(List.empty[MatchedResult]), false)) {
        case ((lineNumber, matchedResults, toIgnore), line) =>
          (
            lineNumber + 1,
            matchedResults.flatMap{prevResults => secretHashScanner.scanLine(line, lineNumber, filePath, toIgnore, serviceDefinedExemptions).map(currentRes => currentRes ++ prevResults) },
            line.contains("LDS ignore")
          )
      }._2
    } catch {
      case ex: Throwable =>
        logger.error(s"error reading $file", ex)
        throw ex
    } finally {
      source.close()
    }

    hashResults
  }

  def applicableScanners(scanners: Seq[RegexScanner], filePath: String, fileExtension: String) =
    scanners.filterNot { scanner =>
      scanner.rule.ignoredExtensions.contains(fileExtension) ||
        scanner.rule.ignoredFiles.exists(pattern => filePath.matches(pattern))
    }

  def fileNameScanner(file: File, filePath: String,  serviceDefinedExemptions: List[RuleExemption]): Future[List[MatchedResult]] = {
    val fileExtension = fileExtensionR.findFirstIn(filePath).getOrElse("")
    val applicableFileNameScanners    = applicableScanners(fileNameScanners, filePath, fileExtension)

    val fileNameResult = applicableFileNameScanners.flatMap {
      _.scanFileName(file.getName, filePath, serviceDefinedExemptions)
    }.toList

    Future.successful(fileNameResult)
  }

  def fileContentScanner(file: File, filePath: String, serviceDefinedExemptions: List[RuleExemption]): Future[List[MatchedResult]] = {
    val fileExtension = fileExtensionR.findFirstIn(filePath).getOrElse("")
    val source = Source.fromFile(file)

    val applicableFileContentScanners = applicableScanners(fileContentScanners, filePath, fileExtension)
    val contentResults = try {
      source
        .getLines()
        .foldLeft((1, List.empty[MatchedResult], false)) {
          case ((lineNumber, matches, isInline), line) =>
            (lineNumber + 1,
              matches ++ applicableFileContentScanners.flatMap {
                _.scanLine(line, lineNumber, filePath, isInline, serviceDefinedExemptions)
              },
              line.contains("LDS ignore")
            )
        }
        ._2
    } catch {
      case ex: Throwable =>
        logger.error(s"error reading $file", ex)
        throw ex
    } finally {
      source.close()
    }

    Future.successful(contentResults)

  }

  def run(explodedZipDir: File, serviceDefinedExemptions: List[RuleExemption]): Future[List[MatchedResult]] = {
    //This now runs sequentially, may want to consider running in parallel in the future.
    getFiles(explodedZipDir)
      .filterNot(_.isDirectory)
      .foldLeft(Future.successful(List.empty[MatchedResult])){ (results, file) =>
        val filePath      = getFilePathRelativeToProjectRoot(explodedZipDir, file)

        for {
          prevResults        <- results
          secretsMatches     <- secretsScanner(file, filePath, serviceDefinedExemptions)
          fileNameMatches    <- fileNameScanner(file, filePath, serviceDefinedExemptions)
          fileContentMatches <- fileContentScanner(file, filePath, serviceDefinedExemptions)
        } yield secretsMatches ++ fileNameMatches ++ fileContentMatches ++ prevResults
      }




  }

  private def createFileContentScanners(rules: Seq[Rule]): Seq[RegexScanner] =
    createScanners(rules, Rule.Scope.FILE_CONTENT)

  private def createFileNameScanners(rules: Seq[Rule]): Seq[RegexScanner] =
    createScanners(rules, Rule.Scope.FILE_NAME)

  private def createScanners(rules: Seq[Rule], ruleScope: String): Seq[RegexScanner] =
    rules.filter(_.scope == ruleScope).map(RegexScanner(_, maxLineLength))

}
