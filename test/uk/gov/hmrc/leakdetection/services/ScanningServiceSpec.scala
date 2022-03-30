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

package uk.gov.hmrc.leakdetection.services

import ammonite.ops.Path
import com.typesafe.config.ConfigFactory
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.mongodb.scala.bson.BsonDocument
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.leakdetection.FileAndDirectoryUtils._
import uk.gov.hmrc.leakdetection.ModelFactory.aSlackConfig
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.{RepositoryInfo, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.{GithubRequestsQueueRepository, RescanRequestsQueueRepository}
import uk.gov.hmrc.leakdetection.scanner.ExemptionChecker
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.time.{Duration, Instant}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ScanningServiceSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar 
    with ArgumentMatchersSugar
    with Results
    with MongoSupport
    with IntegrationPatience {

  "scanRepository" should {

    "scan the git repository and return a report with found violations" in new TestSetup {

      override val privateRules = List(rules.usesNulls, rules.checksInPrivateKeys)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 2
      report.rulesViolated     shouldBe Map(RuleId("rule-1") -> 1, RuleId("rule-2") -> 1)
    }

    "scan a git repository and ignore a rule with the filename included in the ignoredFiles property" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeysExempted, rules.usesNullExempted)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0
    }

    "scan a git repository and ignore a rule in multiple files included in the ignoredFiles property" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeysExempted, rules.usesUnencryptedKey)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0
    }

    "scan a git repository and ignore a file matching a regex included in the ignoredFiles property" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeysExempted, rules.usesUnencryptedKeyRegex)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 0
    }

    "scan a git repository and exclude project specific exempted violations" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeys)

      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-2'
           |    filePath: ${relativePath(file2)}
        """.stripMargin
      }

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 1
      report.rulesViolated     shouldBe empty
      report.exclusions        should not be empty
    }

    "scan the git repository and skip files that match the ignoredExtensions" in new TestSetup {

      override val privateRules = List(rules.checksInPrivateKeys)

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 1
      report.rulesViolated     shouldBe Map(RuleId("rule-2") -> 1)
    }

    "scan the git repository and return a report with unused exemptions" in new TestSetup {
      override val privateRules = List(rules.usesNulls, rules.checksInPrivateKeys)

      writeRepositoryYaml {
        s"""
           |leakDetectionExemptions:
           |  - ruleId: 'rule-1'
           |    filePath: "/dir/missing.file"
        """.stripMargin
      }

      val report = generateReport

      report.author            shouldBe "me"
      report.repoName          shouldBe "repoName"
      report.commitId          shouldBe "3d9c100"
      report.repoUrl           shouldBe "https://github.com/hmrc/repoName"
      report.totalLeaks        shouldBe 2
      report.rulesViolated     shouldBe Map(RuleId("rule-1") -> 1, RuleId("rule-2") -> 1)
      report.unusedExemptions  shouldBe Seq(UnusedExemption("rule-1", "/dir/missing.file", None))
    }

    "trigger alerts" in new TestSetup {
      override val privateRules = List(rules.usesNulls, rules.checksInPrivateKeys)

      file2.getName.contains("id_rsa") shouldBe true
      generateReport.totalLeaks shouldBe 2
      verify(alertingService).alert(any[Report])(any)
    }

    "send a warning alert if there were problems" in new TestSetup {
      when(warningsService.checkForWarnings(any, any, any)).thenReturn(Seq(Warning("", "", Instant.now(), ReportId(""), MissingRepositoryYamlFile.toString)))

      generateReport

      verify(alertingService).alertAboutWarnings(any, any)(any)
    }

    "not send alerts if the branch is not main" in new TestSetup {
      when(warningsService.checkForWarnings(any, any, any)).thenReturn(Seq(Warning("", "", Instant.now(), ReportId(""), MissingRepositoryYamlFile.toString)))

      override val branch = "not-main"
      when(
        artifactService.getZip(
          eqTo(githubSecrets.personalAccessToken),
          eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
          Branch(eqTo(branch)))).thenReturn(Future.successful(Right(unzippedTmpDirectory.toFile)))

      generateReport

      verify(alertingService, times(0)).alertAboutWarnings(any, any)(any)
    }

    "not save the report or trigger any alerts if a dry run" in new TestSetup {
      performScan(true)

      verifyZeroInteractions(reportsService, alertingService)
    }

    "write draft rules to the draft service, not trigger any alerts" in new TestSetup {
      override val privateRules = List(rules.draftRule)
      val argCap = ArgCaptor[Report]

      when(draftService.saveReport(any)).thenReturn(Future.unit)

      val report = generateReport

      report.totalLeaks shouldBe 0

      verify(draftService, times(1)).saveReport(argCap.capture)
      val draftReport = argCap.value
      draftReport.totalLeaks shouldBe 1
      draftReport.rulesViolated.get(RuleId(rules.draftRule.id)) should contain (1)
    }

  }

  "The service" should {
    "process all queued requests" in new TestSetup {
      scanningService.queueRequest(request).futureValue
      queue.collection.countDocuments().toFuture.futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      scanningService.scanAll.futureValue shouldBe 1
      queue.collection.countDocuments().toFuture.futureValue shouldBe 0
    }

    "process one rescan request per scanAll cycle" in new TestSetup {
      scanningService.queueRescanRequest(request).futureValue
      scanningService.queueRescanRequest(request.copy(repositoryName = "another-repo")).futureValue
      queue.collection.countDocuments().toFuture.futureValue shouldBe 0
      rescanQueue.collection.countDocuments().toFuture.futureValue shouldBe 2

      scanningService.scanAll.futureValue shouldBe 1

      rescanQueue.collection.countDocuments().toFuture.futureValue shouldBe 1
    }

    "recover from exceptions expanding the zip and mark the item as failed" in new TestSetup {
      scanningService.queueRequest(request).futureValue
      queue.collection.countDocuments().toFuture.futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      when(
        artifactService.getZip(
          eqTo(githubSecrets.personalAccessToken),
          eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
          Branch(eqTo("main")))).thenThrow(new RuntimeException("Some error"))

      scanningService.scanAll.futureValue shouldBe 0
      queue.count(ProcessingStatus.Failed).futureValue shouldBe 1
    }

    "recover from exceptions saving a report and mark the item as failed" in new TestSetup {
      scanningService.queueRequest(request).futureValue
      queue.collection.countDocuments().toFuture.futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      when(reportsService.saveReport(any)).thenThrow(new RuntimeException("Some error"))

      scanningService.scanAll.futureValue shouldBe 0
      queue.count(ProcessingStatus.Failed).futureValue shouldBe 1
    }

    "recover from failures and mark the item as failed" in new TestSetup {
      scanningService.queueRequest(request).futureValue
      queue.collection.countDocuments().toFuture.futureValue shouldBe 1

      Thread.sleep(1) // the request is pulled from the queue only if current time is > than the insertion time

      when(reportsService.saveReport(any)).thenReturn(Future.failed(new RuntimeException("Some error")))

      scanningService.scanAll.futureValue shouldBe 0
      queue.count(ProcessingStatus.Failed).futureValue shouldBe 1
    }
  }

  trait TestSetup {

    val now         = Instant.now() // new DateTime(0, DateTimeZone.UTC)
    val id          = ReportId.random
    implicit val hc = HeaderCarrier()

    def generateReport = performScan(false)

    def branch = "main"

    def performScan(dryRun: Boolean) =
      scanningService
        .scanRepository(
          repository    = Repository("repoName"),
          branch        = Branch(branch),
          isPrivate     = true,
          repositoryUrl = "https://github.com/hmrc/repoName",
          commitId      = "3d9c100",
          authorName    = "me",
          archiveUrl    = "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}",
          dryRun        = dryRun
        )
        .futureValue

    val request = new PayloadDetails(
      repositoryName = "repoName",
      isPrivate      = true,
      authorName     = "me",
      branchRef      = branch,
      repositoryUrl  = "https://github.com/hmrc/repoName",
      commitId       = "some commit id",
      archiveUrl     = "https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}",
      deleted        = false
    )

    val githubSecrets =
      GithubSecrets(
        personalAccessToken = "pat",
        webhookSecretKey    = "a secret"
      )

    object rules {
      val usesNulls =
        Rule(
          id          = "rule-1",
          scope       = "fileContent",
          regex       = "null",
          description = "uses nulls!",
          priority    = Rule.Priority.High
        )

      val usesNullExempted =
        Rule(
          id           = "rule-1",
          scope        = "fileContent",
          regex        = "null",
          description  = "uses nulls!",
          ignoredFiles = List(relativePath(file1))
        )

      val usesNullWithIgnoredExtensions =
        Rule(
          id                = "rule-1",
          scope             = "fileContent",
          regex             = "null",
          description       = "uses nulls!",
          ignoredExtensions = List(".txt")
        )

      val checksInPrivateKeys =
        Rule(
          id          = "rule-2",
          scope       = "fileName",
          regex       = "id_rsa",
          description = "checks-in private key!"
        )

      val checksInPrivateKeysExempted =
        Rule(
          id           = "rule-2",
          scope        = "fileName",
          regex        = "id_rsa",
          description  = "checks-in private key!",
          ignoredFiles = List(relativePath(file2))
        )

      val usesUnencryptedKeyRegex =
        Rule(
          id           = "rule-3",
          scope        = "fileContent",
          regex        = """((?:play\.crypto\.secret(?!\s*(:|=)*\s*ENC\[)).*)""",
          description  = "Unencrypted play.crypto.secret",
          ignoredFiles = List("^\\/.*application.conf")
        )

      val usesUnencryptedKey =
        Rule(
          id           = "rule-4",
          scope        = "fileContent",
          regex        = """((?:play\.crypto\.secret(?!\s*(:|=)*\s*ENC\[)).*)""",
          description  = "Unencrypted play.crypto.secret",
          ignoredFiles = List("/application.conf", "/test-application.conf")
        )

      val draftRule =
        Rule(
          id           = "draft-rule",
          scope        = "fileContent",
          regex        = """((foo).*)""",
          description  = "detects the word foo in any file",
          draft        = true
        )
    }

    def relativePath(file: File) =
      getFilePathRelativeToProjectRoot(explodedZipDir = unzippedTmpDirectory.toFile, file)

    val privateRules: List[Rule] = Nil

    lazy val config =
      Cfg(
        allRules                  = AllRules(Nil, privateRules),
        githubSecrets             = githubSecrets,
        maxLineLength             = Int.MaxValue,
        clearingCollectionEnabled = false,
        warningMessages           = Map.empty,
        alerts                    = Alerts(aSlackConfig)
      )

    lazy val configLoader = new ConfigLoader {
      val cfg = config
    }

    val artifactService = mock[ArtifactService]
    val reportsService  = mock[ReportsService]
    val leaksService    = mock[LeaksService]
    val warningsService = mock[WarningsService]
    val activeBranchesService = mock[ActiveBranchesService]
    val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]

    val queue = new GithubRequestsQueueRepository(Configuration(ConfigFactory.empty), mongoComponent) {
      override val inProgressRetryAfter: Duration = Duration.ofHours(1)
      override lazy val retryIntervalMillis: Long = 10000L
    }

    queue.collection.deleteMany(BsonDocument()).toFuture.futureValue

    val rescanQueue = new RescanRequestsQueueRepository(Configuration(ConfigFactory.empty), mongoComponent) {
      override val inProgressRetryAfter: Duration = Duration.ofHours(1)
      override lazy val retryIntervalMillis: Long      = 10000L
    }

    rescanQueue.collection.deleteMany(BsonDocument()).toFuture.futureValue

    val unzippedTmpDirectory = Files.createTempDirectory("unzipped_")
    val projectDirectory     = Files.createTempDirectory(unzippedTmpDirectory, "repoName")

    val applicationConf = Files.createFile(Path(s"$projectDirectory/application.conf").toNIO).toFile
    write("""play.crypto.secret="Htt5cyxh8"""", applicationConf)

    val testApplicationConf = Files.createFile(Path(s"$projectDirectory/test-application.conf").toNIO).toFile
    write("""play.crypto.secret="Htt5cyxh8"""", testApplicationConf)

    val file1 = Files.createTempFile(projectDirectory, "test1", ".txt").toFile
    write("package foo \n var x = null", file1)

    val file2 = Files.createTempFile(projectDirectory, "test2", "id_rsa").toFile

    def writeRepositoryYaml(contents: String): Unit = {
      val projectConfigurationYaml = Files.createFile(Path(s"$projectDirectory/repository.yaml").toNIO).toFile
      write(contents, projectConfigurationYaml)
    }

    when(reportsService.saveReport(any)).thenReturn(Future.successful(()))
    when(leaksService.saveLeaks(any[Repository], any[Branch], any)).thenReturn(Future.successful(()))
    when(warningsService.saveWarnings(any[Repository], any[Branch], any)).thenReturn(Future.successful(()))
    when(warningsService.checkForWarnings(any, any, any)).thenReturn(Seq.empty)
    when(activeBranchesService.markAsActive(any[Repository], any[Branch], any[ReportId])).thenReturn(Future.successful(()))
    when(teamsAndRepositoriesConnector.repo(any)).thenReturn(Future.successful(Some(RepositoryInfo("", true, "main"))))

    when(
      artifactService.getZip(
        eqTo(githubSecrets.personalAccessToken),
        eqTo("https://api.github.com/repos/hmrc/repoName/{archive_format}{/ref}"),
        Branch(eqTo(branch)))).thenReturn(Future.successful(Right(unzippedTmpDirectory.toFile)))

    val alertingService = mock[AlertingService]
    when(alertingService.alert(any)(any)).thenReturn(Future.successful(()))
    when(alertingService.alertAboutWarnings(any, any)(any)).thenReturn(Future.successful(()))

    val configuration = Configuration()

    val draftService = mock[DraftReportsService]

    lazy val scanningService =
      new ScanningService(
        artifactService,
        configLoader,
        reportsService,
        draftService,
        leaksService,
        alertingService,
        queue,
        rescanQueue,
        warningsService,
        activeBranchesService,
        new ExemptionChecker(),
        teamsAndRepositoriesConnector)
  }

  def write(content: String, destination: File) =
    new PrintWriter(destination) {
      write(content);
      close()
    }
}
