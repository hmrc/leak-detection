package uk.gov.hmrc.leakdetection.services

import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model.{Leak, ReportId, RepositorySummary, RuleSummary}
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LeaksServiceSpec extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[Leak] with MockitoSugar {

  "Leaks service" should {
    "generate rule summaries as groups of leaks by repository then by rule" in {
      val timestamp = Instant.now.minus(2, HOURS)

      repository.collection.insertMany(
        Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture.futureValue

      val results = leaksService.getRuleSummaries(None, None).futureValue

      results shouldBe Seq(
        RuleSummary(aRule.copy(id = "rule-1"), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 1),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2))
        ),
        RuleSummary(aRule.copy(id = "rule-2"), Seq(RepositorySummary("repo1", timestamp, timestamp, 1))),
        RuleSummary(aRule.copy(id = "rule-3"), Seq())
      )
    }

    "only include repos for the rule if one is provided" in {
      val timestamp = Instant.now.minus(2, HOURS)

      repository.collection.insertMany(
        Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture.futureValue

      val results = leaksService.getRuleSummaries(Some("rule-1"), None).futureValue

      results shouldBe Seq(
        RuleSummary(aRule.copy(id = "rule-1"), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 1),
          RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2))
        ),
        RuleSummary(aRule.copy(id = "rule-2"), Seq()),
        RuleSummary(aRule.copy(id = "rule-3"), Seq())
      )
    }

    "only include repos that belong to the team if one is provided" in {
      val timestamp = Instant.now.minus(2, HOURS)

      repository.collection.insertMany(
        Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture.futureValue

      when(teamsAndReposConnector.team("team1"))
        .thenReturn(Future.successful(Some(Team("team1", None, None, None, Some(Map("Service" -> Seq("repo1")))))))

      val results = leaksService.getRuleSummaries(None, Some("team1")).futureValue

      results shouldBe Seq(
        RuleSummary(aRule.copy(id = "rule-1"), Seq(
          RepositorySummary("repo1", timestamp, timestamp, 1))
        ),
        RuleSummary(aRule.copy(id = "rule-2"), Seq(RepositorySummary("repo1", timestamp, timestamp, 1))),
        RuleSummary(aRule.copy(id = "rule-3"), Seq())
      )
    }
  }

  def aLeak = Leak("repoName", "", Instant.now(), ReportId("reportId"), "ruleId", "description", "/file/path", Scope.FILE_CONTENT, 1, "url", "abc = 123", List(Match(3, 7)), "high")

  def aRule = Rule(
    id = "rule",
    scope = Scope.FILE_CONTENT,
    regex = "regex",
    description = "description"
  )

  override val repository = new LeakRepository(mongoComponent)

  lazy val teamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
  lazy val ruleService = mock[RuleService]

  when(ruleService.getAllRules()).thenReturn(Seq(
    aRule.copy(id = "rule-1"),
    aRule.copy(id = "rule-2"),
    aRule.copy(id = "rule-3")
  ))

  val leaksService = new LeaksService(ruleService, repository, teamsAndReposConnector)

}
