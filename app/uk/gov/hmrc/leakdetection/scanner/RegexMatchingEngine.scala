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
import java.nio.charset.StandardCharsets

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{FileFileFilter, TrueFileFilter}
import uk.gov.hmrc.leakdetection.config.{Rule, RuleExemption}
import uk.gov.hmrc.leakdetection.services.RulesExemptionService

import scala.collection.JavaConverters.collectionAsScalaIterableConverter

case class Result(filePath: String, scanResults: MatchedResult)

class RegexMatchingEngine() {

  import FileAndDirectoryUtils._

  def run(explodedZipDir: File, rules: Seq[Rule], exemptions: Seq[RuleExemption]): List[Result] = {
    val fileContentScanners = createFileContentScanners(rules)
    val fileNameScanners    = createFileNameScanners(rules)

    val filesAndDirs: Iterable[File] = getFiles(explodedZipDir)

    val results = filesAndDirs
      .filterNot(_.isDirectory)
      .par
      .flatMap { file =>
        val fileContent                 = getFileContents(file)
        def toResult(mr: MatchedResult) = Result(getPath(explodedZipDir, file), mr)

        val contentResults = fileContentScanners.flatMap { _.scanFileContent(fileContent).map(toResult) }
        val fileNameResult = fileNameScanners.flatMap { _.scanFileName(file.getName).map(toResult) }

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

  private def getPath(explodedZipDir: File, file: File): String = {
    val strippedTmpDir   = file.getAbsolutePath.stripPrefix(explodedZipDir.getAbsolutePath)
    val strippedRepoName = strippedTmpDir.substring(strippedTmpDir.indexOf('/', 1))

    strippedRepoName
  }
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

  def getFileContents(file: File) =
    FileUtils.readFileToString(file, StandardCharsets.UTF_8)
}
