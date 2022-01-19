package uk.gov.hmrc.leakdetection.services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.model.{Leak, ReportId, RepositorySummary, RuleSummary}
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global

class LeaksServiceSpec extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[Leak] {

  "Leaks service" should {
    "generate rule summaries as groups of leaks by repository then by rule" in {
      val timestamp = Instant.now.minus(2, HOURS)

      repository.collection.insertMany(
        Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture.futureValue

      val results = leaksService.getRuleSummaries.futureValue

      results shouldBe Seq(
        RuleSummary(aRule.copy(id = "rule-1"), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 1),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2))
        ),
        RuleSummary(aRule.copy(id = "rule-2"), Seq(RepositorySummary("repo1", timestamp, timestamp, 1))),
        RuleSummary(aRule.copy(id = "rule-3"), Seq())
      )
    }
  }

  override val repository = new LeakRepository(mongoComponent)

  def aLeak = Leak("repoName", "", Instant.now(), ReportId("reportId"), "ruleId", "description", "/file/path", Scope.FILE_CONTENT, 1, "url", "abc = 123", List(Match(3, 7)), "high")

  def aRule = Rule(
    id = "rule",
    scope = Scope.FILE_CONTENT,
    regex = "regex",
    description = "description"
  )

  lazy val config =
    Cfg(
      allRules = AllRules(List(
        aRule.copy(id = "rule-1"),
        aRule.copy(id = "rule-2"),
        aRule.copy(id = "rule-3")
      ), List()),
      githubSecrets = GithubSecrets("accessToken", "secretKey"),
      maxLineLength = Int.MaxValue,
      clearingCollectionEnabled = false,
      github = Github("", "")
    )

  lazy val configLoader = new ConfigLoader {
    val cfg = config
  }

  val leaksService = new LeaksService(configLoader, repository)

}
