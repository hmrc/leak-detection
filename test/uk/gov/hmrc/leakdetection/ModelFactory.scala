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

package uk.gov.hmrc.leakdetection

import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.leakdetection.config.Rule.{Priority, Scope}
import uk.gov.hmrc.leakdetection.config.{Rule, SlackConfig}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.scanner.{Match, MatchedResult}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Random

object ModelFactory:

  def aString(s: String = ""): String =
    s + "_" + Random.alphanumeric.take(10).mkString

  def aPositiveInt: Int =
    Random.nextInt(Int.MaxValue)

  def few[T](f: () => T): List[T] =
    List.fill(Random.nextInt(5) + 1)(f())

  def maybe[T](t: T): Option[T] =
    if aBoolean then Some(t) else None

  def aBoolean: Boolean =
    Random.nextBoolean()

  def anInstant: Instant =
    Instant.now().truncatedTo((ChronoUnit.MILLIS))

  def aPushUpdate: PushUpdate =
    PushUpdate(
      repositoryName = aString("repositoryName"),
      isPrivate      = aBoolean,
      isArchived     = false,
      authorName     = aString("author"),
      branchRef      = aString("ref"),
      repositoryUrl  = aString("repo-url"),
      commitId       = aString("commitId"),
      archiveUrl     = aString("archiveUrl"),
      runMode        = None
    )

  def aPushDelete: PushDelete =
    PushDelete(
      repositoryName = aString("repositoryName"),
      authorName     = aString("author"),
      repositoryUrl  = aString("repo-url"),
      branchRef      = aString("ref"),
    )

  def aDeleteRepositoryEvent: RepositoryEvent =
    RepositoryEvent(
      repositoryName = aString("repositoryName"),
      action         = "deleted"
    )

  def anArchivedRepositoryEvent: RepositoryEvent =
    RepositoryEvent(
      repositoryName = aString("repositoryName"),
      action         = "archived"
    )

  def aScope: String =
    if aBoolean then
      Rule.Scope.FILE_CONTENT
    else
      Rule.Scope.FILE_NAME

  def aMatchedResult: MatchedResult =
    MatchedResult(
      scope       = aScope,
      lineText    = aString("lineText"),
      lineNumber  = aPositiveInt,
      ruleId      = aString("ruleId"),
      description = aString("description"),
      matches     = List(Match(10, 14)),
      priority    = Rule.Priority.Low,
      filePath    = aString("file-path")
    )

  def aReport(repoName: String = aString("repositoryName")): Report =
    val results = few(() => aMatchedResult)
    Report.createFromMatchedResults(
      repositoryName = repoName,
      repositoryUrl  = aString("repo"),
      commitId       = aString("commitId"),
      authorName     = aString("author"),
      branch         = aString("ref"),
      results        = results,
      unusedExemptions = Nil,
      timestamp      = anInstant
      )

  def aLeak(repoName: String = aString("repositoryName"), branch: String = aString("branch")): Leak =
    Leak(repoName, branch,Instant.now(), ReportId(aString("reportId")), aString("rule-"), aString(), aString(""), Scope.FILE_CONTENT, aPositiveInt, aString("/"), aString(), few(() => Match( aPositiveInt, aPositiveInt)), Priority.Low, false, None)


  def aReportWithLeaks(repoName: String = aString("repositoryName")): Report =
    aReport(repoName).copy(totalLeaks = 1, rulesViolated = Map(RuleId("rule1") -> 1))

  def aReportWithoutLeaks(repoName: String = aString("repositoryName")): Report =
    aReport(repoName).copy(totalLeaks = 0, rulesViolated = Map.empty)

  val aSlackConfig: SlackConfig =
    SlackConfig(
      enabled             = true,
      adminChannel        = "#the-admin-channel",
      defaultAlertChannel = "#the-channel",
      username            = "leak-detection",
      iconEmoji           = ":closed_lock_with_key:",
      alertChannelEnabled  = true,
      repositoryChannelEnabled  = true,
      messageText         = "Do not panic, but there is a leak!",
      leakDetectionUri    = "https://somewhere",
      howToResolveUri     = "https://somewhere",
      removeSensitiveInfoUri = "https://somewhere-else",
      failureText         = "Failure for {repo} with message - {failureMessage}",
      warningText         = "Warning for {repo} with message - {warningMessage}",
      seeReportText       = " See {reportLink}",
      howToResolveText    = " To resolve see {leakResolutionLink} and {removeSensitiveInfoLink}",
      warningsToAlert     = Seq.empty
    )

  val pushUpdateWrites: Writes[PushUpdate] =
    Writes[PushUpdate]: pushUpdate =>
      import pushUpdate._
      Json.obj(
        "ref"     -> s"refs/heads/$branchRef",
        "after"   -> commitId,
        "repository" -> Json
          .obj("name" -> repositoryName, "url" -> repositoryUrl, "archive_url" -> archiveUrl, "private" -> isPrivate, "archived" -> isArchived),
        "pusher" -> Json.obj("name" -> authorName)
      )

  val pushDeleteWrites: Writes[PushDelete] =
    Writes[PushDelete]: pushDelete =>
      import pushDelete._
      Json.obj(
        "ref"        -> s"refs/heads/$branchRef",
        "pusher"     -> Json.obj("name" -> authorName),
        "repository" -> Json.obj("name" -> repositoryName, "url" -> repositoryUrl)
      )

  val repositoryEventWrites: Writes[RepositoryEvent] =
    Writes[RepositoryEvent]: repositoryEvent =>
      import repositoryEvent._
      Json.obj(
        "repository" -> Json.obj("name" -> repositoryName),
        "action" -> action
      )
