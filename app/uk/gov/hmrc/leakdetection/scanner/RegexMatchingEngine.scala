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

import play.api.Logging
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption}

import java.io.File
import java.nio.charset.CodingErrorAction
import scala.collection.parallel.CollectionConverters.*
import scala.io.{Codec, Source}
import scala.util.matching.Regex

case class Result(filePath: String, scanResults: MatchedResult)

class RegexMatchingEngine(rules: List[Rule], maxLineLength: Int) extends Logging:

  import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils._

  val fileContentScanners: Seq[RegexScanner] = createFileContentScanners(rules)
  val fileNameScanners: Seq[RegexScanner]    = createFileNameScanners(rules)
  val fileExtensionR: Regex                  = """\.[A-Za-z0-9]+$""".r

  given codec: Codec = Codec("UTF-8")
  codec.onMalformedInput(CodingErrorAction.IGNORE)
  codec.onUnmappableCharacter(CodingErrorAction.IGNORE)


  def run(explodedZipDir: File, serviceDefinedExemptions: List[RuleExemption]): List[MatchedResult] =

    getFiles(explodedZipDir)
      .filterNot(_.isDirectory)
      .par
      .flatMap: file =>
        val filePath      = getFilePathRelativeToProjectRoot(explodedZipDir, file)
        val fileExtension = fileExtensionR.findFirstIn(filePath).getOrElse("")

        def applicableScanners(scanners: Seq[RegexScanner]) =
          scanners.filterNot: scanner =>
            scanner.rule.ignoredExtensions.contains(fileExtension) ||
            scanner.rule.ignoredFiles.exists(pattern => filePath.matches(pattern))

        val applicableFileContentScanners = applicableScanners(fileContentScanners)
        val applicableFileNameScanners    = applicableScanners(fileNameScanners)

        val source = Source.fromFile(file)

        val contentResults: Seq[MatchedResult] =
          try
            source
              .getLines()
              .foldLeft((1, Seq.empty[MatchedResult], false)) {
                case ((lineNumber, acc, isInline), line) =>
                  (lineNumber + 1, acc ++ applicableFileContentScanners.flatMap {
                    _.scanLine(line, lineNumber, filePath, isInline, serviceDefinedExemptions)
                  }, line.contains("LDS ignore"))
              }
              ._2
          catch
            case ex: Throwable =>
              logger.error(s"error reading $file", ex)
              throw ex
          finally
            source.close()

        val fileNameResult: Seq[MatchedResult] =
          applicableFileNameScanners.flatMap:
            _.scanFileName(file.getName, filePath, serviceDefinedExemptions)

        checkForMultiline(file, contentResults) ++ fileNameResult
      .toList

  private def checkForMultiline(file: File, results: Seq[MatchedResult]): Seq[MatchedResult] =
    val source = Source.fromFile(file)

    try
      val lines = source.getLines().toList.zipWithIndex.map{ case (line, idx) => (idx + 1, line) }.toMap

      results.flatMap: result =>
        val matchedLine = result.lineText
        val nextLine = lines.getOrElse(result.lineNumber + 1, default = "")

        val scanner = fileContentScanners.find(_.rule.id == result.ruleId)

        scanner.flatMap(
          _.scanLine(
            line                     = matchedLine + nextLine,
            lineNumber               = result.lineNumber,
            filePath                 = result.filePath,
            inlineExemption          = false,
            serviceDefinedExemptions = Seq.empty
          )
        ).fold(Seq.empty[MatchedResult])(_ => Seq(result))
    catch
      case ex: Throwable =>
        logger.error(s"error reading $file", ex)
        throw ex
    finally
      source.close()

  private def createFileContentScanners(rules: Seq[Rule]): Seq[RegexScanner] =
    createScanners(rules, Rule.Scope.FILE_CONTENT)

  private def createFileNameScanners(rules: Seq[Rule]): Seq[RegexScanner] =
    createScanners(rules, Rule.Scope.FILE_NAME)

  private def createScanners(rules: Seq[Rule], ruleScope: String): Seq[RegexScanner] =
    rules.filter(_.scope == ruleScope).map(RegexScanner(_, maxLineLength))
