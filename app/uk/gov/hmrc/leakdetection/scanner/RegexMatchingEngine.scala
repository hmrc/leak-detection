/*
 * Copyright 2018 HM Revenue & Customs
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

import java.io.File
import java.nio.charset.{CodingErrorAction, StandardCharsets}

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{FileFileFilter, TrueFileFilter}
import uk.gov.hmrc.leakdetection.config.Rule
import uk.gov.hmrc.leakdetection.services.RulesExemptionParser

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.io.{Codec, Source}

case class Result(filePath: String, scanResults: MatchedResult)

class RegexMatchingEngine(rules: List[Rule]) {

  import FileAndDirectoryUtils._

  val fileContentScanners = createFileContentScanners(rules)
  val fileNameScanners    = createFileNameScanners(rules)
  val fileExtensionR      = """\.[A-Za-z0-9]+$""".r

  implicit val codec = Codec("UTF-8")
  codec.onMalformedInput(CodingErrorAction.IGNORE)
  codec.onUnmappableCharacter(CodingErrorAction.IGNORE)

  def run(explodedZipDir: File): List[Result] = {

    val serviceDefinedExemptions =
      RulesExemptionParser.parseServiceSpecificExemptions(FileAndDirectoryUtils.getSubdirName(explodedZipDir))

    val filesAndDirs: Iterable[File] = getFiles(explodedZipDir)

    val results = filesAndDirs
      .filterNot(_.isDirectory)
      .par
      .flatMap { file =>
        val filePath      = getFilePathRelativeToProjectRoot(explodedZipDir, file)
        val fileExtension = fileExtensionR.findFirstIn(filePath).getOrElse("")

        def applicableScanners(scanners: Seq[RegexScanner]) =
          scanners.filterNot { scanner =>
            scanner.rule.ignoredExtensions.contains(fileExtension) ||
            scanner.rule.ignoredFiles.exists(pattern => filePath.matches(pattern)) ||
            serviceDefinedExemptions
              .find(_.ruleId == scanner.rule.id)
              .fold(false)(exemption => exemption.filePath == filePath)
          }

        val applicableFileContentScanners = applicableScanners(fileContentScanners)
        val applicableFileNameScanners    = applicableScanners(fileNameScanners)

        val contentResults: Seq[Result] = Source
          .fromFile(file)
          .getLines
          .foldLeft(1 -> Seq.empty[Result]) {
            case ((lineNumber, acc), line) =>
              lineNumber + 1 -> (acc ++ applicableFileContentScanners.flatMap {
                _.scanLine(line, lineNumber).map(mr => Result(filePath, mr))
              })
          }
          ._2

        val fileNameResult: Seq[Result] = applicableFileNameScanners.flatMap {
          _.scanFileName(file.getName).map(mr => Result(filePath, mr))
        }

        contentResults ++ fileNameResult

      }
      .toList

    results
  }

  private def createFileContentScanners(rules: Seq[Rule]): Seq[RegexScanner] =
    createScanners(rules, Rule.Scope.FILE_CONTENT)

  private def createFileNameScanners(rules: Seq[Rule]): Seq[RegexScanner] =
    createScanners(rules, Rule.Scope.FILE_NAME)

  private def createScanners(rules: Seq[Rule], ruleScope: String): Seq[RegexScanner] =
    rules.filter(_.scope == ruleScope).map(new RegexScanner(_))

}

object FileAndDirectoryUtils {
  def getFiles(explodedZipDir: File): Iterable[File] =
    FileUtils
      .listFilesAndDirs(
        explodedZipDir,
        FileFileFilter.FILE,
        TrueFileFilter.INSTANCE
      )
      .asScala

  def getFileContents(file: File): String =
    FileUtils.readFileToString(file, StandardCharsets.UTF_8)

  def getFilePathRelativeToProjectRoot(explodedZipDir: File, file: File): String = {
    val strippedTmpDir   = file.getAbsolutePath.stripPrefix(explodedZipDir.getAbsolutePath)
    val strippedRepoName = strippedTmpDir.stripPrefix(File.separator + getSubdirName(explodedZipDir).getName)

    strippedRepoName
  }

  def getSubdirName(parentDir: File): File =
    parentDir.listFiles().filter(_.isDirectory).head

}
