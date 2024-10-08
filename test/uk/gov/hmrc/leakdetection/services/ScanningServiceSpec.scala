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

package uk.gov.hmrc.leakdetection.services

import org.apache.pekko.stream.IOOperationIncompleteException
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentCaptor
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.{times, verify, verifyNoInteractions, when}
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mongodb.scala.bson.BsonDocument
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils.*
import uk.gov.hmrc.leakdetection.ModelFactory.aSlackConfig
import uk.gov.hmrc.leakdetection.config.*
import uk.gov.hmrc.leakdetection.connectors.{GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.RunMode.{Draft, Normal}
import uk.gov.hmrc.leakdetection.model.*
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, RescanRequestsQueueRepository}
import uk.gov.hmrc.leakdetection.scanner.ExemptionChecker
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import org.mongodb.scala.SingleObservableFuture
import uk.gov.hmrc.leakdetection.model.WarningMessageType._

import java.io.{File, PrintWriter}
import java.nio.file
import java.nio.file.Files
import java.time.{Duration, Instant}
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ScanningServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with MockitoSugar
     with Results
     with MongoSupport
     with IntegrationPatience:

  "scanRepository" should:

    "scan the git repository and return a report with found violations" in new TestSetup:

      override val privateRules: List[Rule] =
        List(rules.usesNulls, rules.checksInPrivateKeys)

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 2
      report.rulesViolated     shouldBe Map(RuleId("rule-1") -> 1, RuleId("rule-2") -> 1)

    "scan a git repository and ignore a rule with the filename included in the ignoredFiles property" in new TestSetup:

      override val privateRules: List[Rule] =
        List(rules.checksInPrivateKeysExempted, rules.usesNullExempted)

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0

    "scan a git repository and ignore a rule in multiple files included in the ignoredFiles property" in new TestSetup:

      override val privateRules: List[Rule] =
        List(rules.checksInPrivateKeysExempted, rules.usesUnencryptedKey)

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0

    "scan a git repository and ignore a file matching a regex included in the ignoredFiles property" in new TestSetup:

      override val privateRules: List[Rule] =
        List(rules.checksInPrivateKeysExempted, rules.usesUnencryptedKeyRegex)

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0

    "scan a git repository and exclude project specific exempted violations" in new TestSetup:

      override val privateRules: List[Rule] =
        List(rules.checksInPrivateKeys)

      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-2'
           |    filePath: '${relativePath(file2)}'
        """.stripMargin
      }

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0
      report.rulesViolated     shouldBe empty
      report.exclusions        should not be empty

    "scan the git repository and skip files that match the ignoredExtensions" in new TestSetup:

      override val privateRules: List[Rule] =
        List(rules.checksInPrivateKeys)

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 1
      report.rulesViolated     shouldBe Map(RuleId("rule-2") -> 1)

    "scan the git repository and return a report with unused exemptions" in new TestSetup:
      override val privateRules: List[Rule] =
        List(rules.usesNulls, rules.checksInPrivateKeys)

      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-1'
           |    filePath: "/dir/missing.file"
        """.stripMargin
      }

      val report: Report =
        generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 2
      report.rulesViolated     shouldBe Map(RuleId("rule-1") -> 1, RuleId("rule-2") -> 1)
      report.unusedExemptions  shouldBe Seq(UnusedExemption("rule-1", "/dir/missing.file", None))

    "trigger alerts" in new TestSetup:
      override val privateRules: List[Rule] =
        List(rules.usesNulls, rules.checksInPrivateKeys)

      file2.getName.contains("id_rsa") shouldBe true
      generateReport.totalLeaks shouldBe 2
      verify(alertingService).alert(any[Report], any[Boolean])(using any[HeaderCarrier])

    "send a warning alert if there were problems" in new TestSetup:
      when(warningsService.checkForWarnings(any, any, any, any, any, any)).thenReturn(Seq(Warning("", "", Instant.now(), ReportId(""), MissingRepositoryYamlFile.toString)))

      generateReport

      verify(alertingService).alertAboutWarnings(any, any, any[Boolean])(using any[HeaderCarrier])

    "not send alerts if the branch is not main" in new TestSetup:
      when(warningsService.checkForWarnings(any, any, any, any, any, any)).thenReturn(Seq(Warning("", "", Instant.now(), ReportId(""), MissingRepositoryYamlFile.toString)))

      override val branch = "not-main"
      when(
        githubConnector.getZip(
          eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
          Branch(eqTo(branch)),
          any[java.nio.file.Path])) .thenReturn(Future.successful(Right(unzippedTmpDirectory.toFile)))

      generateReport

      verify(alertingService, times(0)).alertAboutWarnings(any, any, any[Boolean])(using any[HeaderCarrier])

    "scan in draft mode" should:
      "report on active rule leaks without storing in the leaks collection" in new TestSetup:
        override val privateRules: List[Rule] =
          List(rules.usesNulls)

        val argCap: ArgumentCaptor[Report] =
          ArgumentCaptor.forClass(classOf[Report])

        when(draftService.saveReport(any)).thenReturn(Future.unit)

        val report: Report =
          performScan(Draft)

        report.totalLeaks shouldBe 1

        verify(leaksService, times(0)).saveLeaks(any[Repository], any[Branch], any)
        verify(draftService, times(1)).saveReport(argCap.capture)

        val draftReport: Report =
          argCap.getValue

        draftReport.totalLeaks shouldBe 1
        draftReport.rulesViolated.get(RuleId(rules.usesNulls.id)) should contain(1)

      "report on warnings without storing them in the warnings collection" in new TestSetup:
        val argCap: ArgumentCaptor[Report] =
          ArgumentCaptor.forClass(classOf[Report])

        when(warningsService.checkForWarnings(any, any, any, any, any, any)).thenReturn(Seq(Warning("", "", Instant.now(), ReportId(""), MissingRepositoryYamlFile.toString)))
        when(draftService.saveReport(any)).thenReturn(Future.unit)

        val report: Report =
          performScan(Draft)

        report.totalWarnings shouldBe 1

        verify(warningsService, times(0)).saveWarnings(any[Repository], any[Branch], any)
        verify(draftService, times(1)).saveReport(argCap.capture)

        val draftReport: Report =
          argCap.getValue

        draftReport.totalWarnings shouldBe 1

      "Include draft rule violations on the report" in new TestSetup:
        override val privateRules: List[Rule] =
          List(rules.draftRule)

        val argCap: ArgumentCaptor[Report] =
          ArgumentCaptor.forClass(classOf[Report])

        when(draftService.saveReport(any)).thenReturn(Future.unit)

        val report: Report =
          performScan(Draft)

        report.totalLeaks shouldBe 1

        verify(draftService, times(1)).saveReport(argCap.capture)

        val draftReport: Report =
          argCap.getValue

        draftReport.totalLeaks shouldBe 1
        draftReport.rulesViolated.get(RuleId(rules.draftRule.id)) should contain(1)

      "not trigger any alerts or store a report in the reports collection" in new TestSetup:
        override val privateRules: List[Rule] =
          List(rules.draftRule)

        when(draftService.saveReport(any)).thenReturn(Future.unit)

        val report: Report =
          performScan(Draft)

        report.totalLeaks shouldBe 1

        verifyNoInteractions(reportsService, alertingService)

  "The service" should:
    "process all queued requests" in new TestSetup:
      when(reportsService.reportExists(any)).thenReturn(Future.successful(false))
      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      scanningService.scanAll.futureValue shouldBe 1
      queue.collection.countDocuments().toFuture().futureValue shouldBe 0

    "not process duplicate requests when report exists" in new TestSetup:
      when(reportsService.reportExists(any))
        .thenReturn(Future.successful(false), Future.successful(true))
      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      scanningService.scanAll.futureValue shouldBe 1
      queue.collection.countDocuments().toFuture().futureValue shouldBe 0

      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 0
      scanningService.scanAll.futureValue shouldBe 0

    "not process duplicate requests when request already queued" in new TestSetup:
      when(reportsService.reportExists(any)).thenReturn(Future.successful(false))
      scanningService.queueDistinctRequest(request).futureValue
      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      scanningService.scanAll.futureValue shouldBe 1
      queue.collection.countDocuments().toFuture().futureValue shouldBe 0

    "recover from exceptions expanding the zip and mark the item as failed" in new TestSetup:
      when(reportsService.reportExists(any)).thenReturn(Future.successful(false))
      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      when(
        githubConnector.getZip(
          eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
          Branch(eqTo("main")),
          any[java.nio.file.Path])).thenThrow(new IOOperationIncompleteException(1, new TimeoutException("Some error")))

      scanningService.scanAll.futureValue shouldBe 0
      queue.count(ProcessingStatus.Failed).futureValue shouldBe 1

    "recover from exceptions saving a report and mark the item as failed" in new TestSetup:
      when(reportsService.reportExists(any)).thenReturn(Future.successful(false))
      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      when(reportsService.saveReport(any)).thenThrow(new RuntimeException("Some error"))

      scanningService.scanAll.futureValue shouldBe 0
      queue.count(ProcessingStatus.Failed).futureValue shouldBe 1

    "recover from failures and mark the item as failed" in new TestSetup:
      when(reportsService.reportExists(any)).thenReturn(Future.successful(false))
      scanningService.queueDistinctRequest(request).futureValue
      queue.collection.countDocuments().toFuture().futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      when(reportsService.saveReport(any)).thenReturn(Future.failed(new RuntimeException("Some error")))


      scanningService.scanAll.futureValue shouldBe 0
      queue.count(ProcessingStatus.Failed).futureValue shouldBe 1

  trait TestSetup:

    val now: Instant =
      Instant.now()

    val id: ReportId =
      ReportId.random

    given HeaderCarrier = HeaderCarrier()

    def generateReport: Report =
      performScan(Normal)

    def branch: String =
      "main"

    def performScan(runMode: RunMode): Report =
      scanningService
        .scanRepository(
          repository    = Repository("repoName"),
          branch        = Branch(branch),
          isPrivate     = true,
          isArchived    = false,
          repositoryUrl = "https://github.com/hmrc/repoName",
          commitId      = "3d9c100",
          authorName    = "me",
          archiveUrl    = "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}",
          runMode       = runMode
        )
        .futureValue

    val request: PushUpdate =
      PushUpdate(
        repositoryName = "repoName",
        isPrivate      = true,
        isArchived     = false,
        authorName     = "me",
        branchRef      = branch,
        repositoryUrl  = "https://github.com/hmrc/repoName",
        commitId       = "some commit id",
        archiveUrl     = "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}",
        runMode        = None
      )

    val githubSecrets: GithubSecrets =
      GithubSecrets(
        personalAccessToken = "pat",
      )

    object rules:
      val usesNulls: Rule =
        Rule(
          id          = "rule-1",
          scope       = "fileContent",
          regex       = "null",
          description = "uses nulls!",
          priority    = Rule.Priority.High
        )

      val usesNullExempted: Rule =
        Rule(
          id           = "rule-1",
          scope        = "fileContent",
          regex        = "null",
          description  = "uses nulls!",
          ignoredFiles = List(relativePath(file1))
        )

      val usesNullWithIgnoredExtensions: Rule =
        Rule(
          id                = "rule-1",
          scope             = "fileContent",
          regex             = "null",
          description       = "uses nulls!",
          ignoredExtensions = List(".txt")
        )

      val checksInPrivateKeys: Rule =
        Rule(
          id          = "rule-2",
          scope       = "fileName",
          regex       = "id_rsa",
          description = "checks-in private key!"
        )

      val checksInPrivateKeysExempted: Rule =
        Rule(
          id           = "rule-2",
          scope        = "fileName",
          regex        = "id_rsa",
          description  = "checks-in private key!",
          ignoredFiles = List(relativePath(file2))
        )

      val usesUnencryptedKeyRegex: Rule =
        Rule(
          id           = "rule-3",
          scope        = "fileContent",
          regex        = """((?:play\.crypto\.secret(?!\s*(:|=)*\s*ENC\[)).*)""",
          description  = "Unencrypted play.crypto.secret",
          ignoredFiles = List("^\\/.*application.conf")
        )

      val usesUnencryptedKey: Rule =
        Rule(
          id           = "rule-4",
          scope        = "fileContent",
          regex        = """((?:play\.crypto\.secret(?!\s*(:|=)*\s*ENC\[)).*)""",
          description  = "Unencrypted play.crypto.secret",
          ignoredFiles = List("/application.conf", "/test-application.conf")
        )

      val draftRule: Rule =
        Rule(
          id           = "draft-rule",
          scope        = "fileContent",
          regex        = """((foo).*)""",
          description  = "detects the word foo in any file",
          draft        = true
        )

    def relativePath(file: File): String =
      getFilePathRelativeToProjectRoot(explodedZipDir = unzippedTmpDirectory.toFile, file)

    val privateRules: List[Rule] = Nil

    lazy val appConfig: AppConfig =
      AppConfig(
        allRules                    = AllRules(Nil, privateRules),
        githubSecrets               = githubSecrets,
        maxLineLength               = Int.MaxValue,
        clearingCollectionEnabled   = false,
        warningMessages             = Map.empty,
        alerts                      = Alerts(aSlackConfig),
        timeoutBackoff              = 1.second,
        timeoutBackOffMax           = 1.second,
        timeoutFailureLogAfterCount = 2
      )

    val githubConnector: GithubConnector =
      mock[GithubConnector]

    val reportsService: ReportsService =
      mock[ReportsService]

    val leaksService: LeaksService =
      mock[LeaksService]

    val warningsService: WarningsService =
      mock[WarningsService]

    val activeBranchesService: ActiveBranchesService =
      mock[ActiveBranchesService]

    val teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector =
      mock[TeamsAndRepositoriesConnector]

    val queue: GithubRequestsQueueRepository =
      new GithubRequestsQueueRepository(Configuration(ConfigFactory.empty), mongoComponent):
        override val inProgressRetryAfter: Duration = Duration.ofHours(1)
        override lazy val retryIntervalMillis: Long = 10000L

    queue.collection.deleteMany(BsonDocument()).toFuture().futureValue

    val rescanQueue: RescanRequestsQueueRepository =
      new RescanRequestsQueueRepository(Configuration(ConfigFactory.empty), mongoComponent):
        override val inProgressRetryAfter: Duration = Duration.ofHours(1)
        override lazy val retryIntervalMillis: Long      = 10000L

    rescanQueue.collection.deleteMany(BsonDocument()).toFuture().futureValue

    val unzippedTmpDirectory: file.Path =
      Files.createTempDirectory("unzipped_")

    val projectDirectory: file.Path =
      Files.createTempDirectory(unzippedTmpDirectory, "repoName")

    val applicationConf: File =
      Files.createFile(projectDirectory.resolve("application.conf")).toFile

    write("""play.crypto.secret="Htt5cyxh8"""", applicationConf)

    val testApplicationConf: File =
      Files.createFile(projectDirectory.resolve("test-application.conf")).toFile

    write("""play.crypto.secret="Htt5cyxh8"""", testApplicationConf)

    val file1: File =
      Files.createTempFile(projectDirectory, "test1", ".txt").toFile

    write("package foo \n var x = null", file1)

    val file2: File =
      Files.createTempFile(projectDirectory, "test2", "id_rsa").toFile

    def writeRepositoryYaml(contents: String): Unit =
      val projectConfigurationYaml = Files.createFile(projectDirectory.resolve("repository.yaml")).toFile
      write(contents, projectConfigurationYaml)

    when(reportsService.saveReport(any)).thenReturn(Future.successful(()))
    when(leaksService.saveLeaks(any[Repository], any[Branch], any)).thenReturn(Future.successful(()))
    when(warningsService.saveWarnings(any[Repository], any[Branch], any)).thenReturn(Future.successful(()))
    when(warningsService.checkForWarnings(any, any, any, any, any, any)).thenReturn(Seq.empty)
    when(activeBranchesService.markAsActive(any[Repository], any[Branch], any[ReportId])).thenReturn(Future.successful(()))
    when(teamsAndRepositoriesConnector.repo(any)).thenReturn(Future.successful(Some(TeamsAndRepositoriesConnector.RepositoryInfo("", true, false, "main"))))
    when(githubConnector.getBlame(any[Repository], any[Branch], any[String])).thenReturn(Future.successful(GitBlame(Seq())))
    when(
      githubConnector.getZip(
        eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
        Branch(eqTo(branch)),
        any[java.nio.file.Path])).thenReturn(Future.successful(Right(unzippedTmpDirectory.toFile)))


    val alertingService: AlertingService =
      mock[AlertingService]

    when(alertingService.alert(any, any[Boolean])(using any[HeaderCarrier])).thenReturn(Future.successful(()))
    when(alertingService.alertAboutWarnings(any, any, any[Boolean])(using any[HeaderCarrier])).thenReturn(Future.successful(()))

    val configuration: Configuration =
      Configuration()

    val draftService: DraftReportsService =
      mock[DraftReportsService]

    lazy val scanningService: ScanningService =
      ScanningService(
        githubConnector,
        appConfig,
        reportsService,
        draftService,
        leaksService,
        alertingService,
        queue,
        rescanQueue,
        warningsService,
        activeBranchesService,
        new ExemptionChecker(),
        teamsAndRepositoriesConnector
      )

  def write(content: String, destination: File): PrintWriter =
    new PrintWriter(destination):
      this.write(content)
      close()
