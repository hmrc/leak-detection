package uk.gov.hmrc.leakdetection

import java.io.File
import java.nio.charset.StandardCharsets

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{FileFileFilter, TrueFileFilter}
import scala.collection.JavaConverters.collectionAsScalaIterableConverter

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
