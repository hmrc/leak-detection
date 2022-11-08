/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{FileFileFilter, TrueFileFilter}

import java.io.File
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

object FileAndDirectoryUtils {
  def getFiles(explodedZipDir: File): Iterable[File] =
    FileUtils
      .listFilesAndDirs(
        explodedZipDir,
        FileFileFilter.INSTANCE,
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
    Option(parentDir.listFiles())
      .toList
      .flatten
      .find(_.isDirectory)
      .getOrElse(throw new RuntimeException(s"[$parentDir] directory does not exist is empty"))
}
