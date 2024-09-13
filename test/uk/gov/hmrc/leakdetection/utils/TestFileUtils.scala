/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.utils

import java.nio.file.{Files, Path, StandardOpenOption}

object TestFileUtils:

  def write(path: Path, content: String, createFolders: Boolean = false): Unit =
    if createFolders then Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  def tempDir(dir: Path = null, prefix: String = null, deleteOnExit: Boolean = true): Path =
    val nioPath: Path = dir match
      case null => Files.createTempDirectory(prefix)
      case _ => Files.createTempDirectory(dir, prefix)

    if deleteOnExit then nioPath.toFile.deleteOnExit()
    nioPath

  def makeDir(subdir: Path): Path =
    Files.createDirectory(subdir)