/*
 * Copyright 2017 HM Revenue & Customs
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
import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import uk.gov.hmrc.leakdetection.config.Rule

case class Result(filePath: String, scanResults: MatchedResult)

class RegexMatchingEngine() {

  import FileAndDirectoryUtils._

  def run(explodedZipDir: File, rules: Seq[Rule]): List[Result] = {
    val scanners = rules.map(new RegexScanner(_))

    val filesAndDirs: Iterable[File] = getFiles(explodedZipDir)

    val results = filesAndDirs
      .filterNot(_.isDirectory)
      .par
      .flatMap { file =>
        val fileContent = getFileContents(file)
        scanners.map { scanner =>
          val scanResults: Seq[Result] =
            scanner.scan(fileContent).map(sr => Result(getPath(explodedZipDir, file), sr))
          scanResults
        }
      }
      .flatten
      .toList

    results
  }

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
