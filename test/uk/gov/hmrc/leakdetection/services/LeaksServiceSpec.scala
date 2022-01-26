package uk.gov.hmrc.leakdetection.services

import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.config.Rule.Scope
import uk.gov.hmrc.leakdetection.config._
import uk.gov.hmrc.leakdetection.connectors.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.leakdetection.model._
import uk.gov.hmrc.leakdetection.persistence.LeakRepository
import uk.gov.hmrc.leakdetection.scanner.Match
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LeaksServiceSpec extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[Leak] with MockitoSugar {

  override val repository = new LeakRepository(mongoComponent)

  lazy val teamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
  lazy val ruleService = mock[RuleService]

  val leaksService = new LeaksService(ruleService, repository, teamsAndReposConnector)

  "Leaks service" should {
    val timestamp = Instant.now.minus(2, HOURS)
    "generate summaries as groups of leaks by rule, repository and branch" should {
      when(ruleService.getAllRules()).thenReturn(Seq(
        aRule.copy(id = "rule-1"),
        aRule.copy(id = "rule-2"),
        aRule.copy(id = "rule-3")
      ))

      "include all leaks when no filters applied" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        val results = leaksService.getSummaries(None, None, None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            )),
            RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2, Seq(
              BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1),
              BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the rule if ruleId is provided" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        val results = leaksService.getSummaries(Some("rule-1"), None, None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            )),
            RepositorySummary("repo2", timestamp.minus(3, HOURS), timestamp.minus(1, HOURS), 2, Seq(
              BranchSummary("branch1", ReportId("reportId"), timestamp.minus(3, HOURS), 1),
              BranchSummary("branch2", ReportId("reportId"), timestamp.minus(1, HOURS), 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-2"), Seq()),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the repository if repoName is provided" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        val results = leaksService.getSummaries(None, Some("repo1"), None).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }

      "only include leaks associated the team if teamName is provided" in {
        repository.collection.insertMany(
          Seq(aLeak.copy(repoName = "repo1", ruleId = "rule-1", timestamp = timestamp),
            aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
            aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
        ).toFuture.futureValue

        when(teamsAndReposConnector.team("team1"))
          .thenReturn(Future.successful(Some(Team("team1", None, None, None, Some(Map("Service" -> Seq("repo1")))))))

        val results = leaksService.getSummaries(None, None, Some("team1")).futureValue

        results shouldBe Seq(
          Summary(aRule.copy(id = "rule-1"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(BranchSummary("branch", ReportId("reportId"), timestamp, 1))))
          ),
          Summary(aRule.copy(id = "rule-2"), Seq(
            RepositorySummary("repo1", timestamp, timestamp, 1, Seq(
              BranchSummary("branch", ReportId("reportId"), timestamp, 1)
            ))
          )),
          Summary(aRule.copy(id = "rule-3"), Seq())
        )
      }
    }
    "get leaks for a report" in {
      repository.collection.insertMany(

        Seq(aLeak.copy(repoName = "repo1", reportId = ReportId("otherReport"), ruleId = "rule-1", timestamp = timestamp),
          aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
          aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS)))
      ).toFuture.futureValue

      val results = leaksService.getLeaksForReport(ReportId("reportId")).futureValue

      results shouldBe Seq(
        aLeak.copy(repoName = "repo1", ruleId = "rule-2", timestamp = timestamp),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch1", timestamp = timestamp.minus(3, HOURS)),
        aLeak.copy(repoName = "repo2", ruleId = "rule-1", branch = "branch2", timestamp = timestamp.minus(1, HOURS))
      )
    }
  }

  def aLeak = Leak(
    "repoName",
    "branch",
    Instant.now(),
    ReportId("reportId"),
    "ruleId", "description",
    "/file/path",
    Scope.FILE_CONTENT,
    1,
    "url",
    "abc = 123",
    List(Match(3, 7)),
    "high"
  )

  def aRule = Rule(
    id = "rule",
    scope = Scope.FILE_CONTENT,
    regex = "regex",
    description = "description"
  )
}
