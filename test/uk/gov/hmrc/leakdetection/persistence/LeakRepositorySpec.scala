package uk.gov.hmrc.leakdetection.persistence

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.leakdetection.IncreasingTimestamps
import uk.gov.hmrc.leakdetection.ModelFactory._
import uk.gov.hmrc.leakdetection.model.{Leak, LeakUpdateResult, Report, ReportId, Repository}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import scala.concurrent.ExecutionContext.Implicits.global

class LeakRepositorySpec extends AnyWordSpec
    with Matchers
    with PlayMongoRepositorySupport[Leak]
    with CleanMongoCollectionSupport
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterEach
    with IncreasingTimestamps {

  override val repository = new LeakRepository(mongoComponent)

  "Leak repository" should {

    "return zero inserts and not fail when updating with no leaks" in {
      repository.update("test", "main", Seq.empty).futureValue shouldBe LeakUpdateResult(0,0)
    }
  }
}
