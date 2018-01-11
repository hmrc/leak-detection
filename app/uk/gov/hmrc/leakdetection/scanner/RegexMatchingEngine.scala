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
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption}
import uk.gov.hmrc.leakdetection.services.RulesExemptionService

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.io.{Codec, Source}

case class Result(filePath: String, scanResults: MatchedResult)

class RegexMatchingEngine(rules: List[Rule]) {

  import FileAndDirectoryUtils._

  val fileContentScanners = createFileContentScanners(rules)
  val fileNameScanners    = createFileNameScanners(rules)
  val globalExemptions: List[RuleExemption] =
    for {
      rule        <- rules
      ignoredFile <- rule.ignoredFiles
    } yield {
      RuleExemption(rule.id, ignoredFile)
    }

  implicit val codec = Codec("UTF-8")
  codec.onMalformedInput(CodingErrorAction.IGNORE)
  codec.onUnmappableCharacter(CodingErrorAction.IGNORE)

  def run(explodedZipDir: File): List[Result] = {

    val exemptions = {
      val repoDir                  = FileAndDirectoryUtils.getSubdirName(explodedZipDir)
      val serviceDefinedExemptions = RulesExemptionService.parseServiceSpecificExemptions(repoDir)
      serviceDefinedExemptions ++ globalExemptions
    }

    val filesAndDirs: Iterable[File] = getFiles(explodedZipDir)

    val results = filesAndDirs
      .filterNot(_.isDirectory)
      .par
      .flatMap { file =>
        def toResult(mr: MatchedResult) = Result(getFilePathRelativeToProjectRoot(explodedZipDir, file), mr)

        val contentResults: Seq[Result] = Source
          .fromFile(file)
          .getLines
          .foldLeft(1 -> Seq.empty[Result]) {
            case ((lineNumber, acc), line) =>
              lineNumber + 1 -> (acc ++ fileContentScanners.flatMap {
                _.scanLine(line, lineNumber).map(toResult)
              })
          }
          ._2

        val fileNameResult: Seq[Result] = fileNameScanners.flatMap {
          _.scanFileName(file.getName).map(toResult)
        }

        contentResults ++ fileNameResult

      }
      .toList
      .filterNot(RulesExemptionService.isExempt(exemptions))

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
